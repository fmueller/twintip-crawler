(ns org.zalando.stups.twintip.crawler.jobs
  (:require [org.zalando.stups.friboo.system.cron :refer [def-cron-component]]
            [org.zalando.stups.friboo.log :as log]
            [overtone.at-at :refer [every]]
            [clj-http.lite.client :as client]
            [clojure.data.json :as json]))

(def default-configuration
  {:jobs-cpu-count        1
   :jobs-every-ms         60000
   :jobs-initial-delay-ms 1000})

(defn- add-path
  "Concatenates path elements to an URL."
  [url & path]
  (let [[x & xs] path]
    (if x
      (let [url (if (or
                      (.endsWith url "/")
                      (.startsWith x "/"))
                  (str url x)
                  (str url "/" x))]
        (recur url xs))
      url)))

(defn- fetch-url
  "GETs a JSON document and returns the parsed result."
  ([url]
   (-> (client/get url)
       :body
       json/read-str))
  ([url path]
   (fetch-url (add-path url path))))

(defn fetch-apps
  "Fetches list of all applications."
  [kio-url]
  ; TODO filter only active
  (fetch-url kio-url "/apps"))

(defn- get-app-api-info
  "Fetch information about one application."
  [app-service-url]
  (try
    ; TODO make discovery endpoint configurable
    (let [discovery (fetch-url app-service-url "/.discovery")
          definition-url (get discovery "definition")]
      (try
        (let [definition (fetch-url (add-path app-service-url definition-url))]
          (if (= (get definition "swagger") "2.0")
            {:status     "SUCCESS"
             :type       "swagger-2.0"
             :name       (get-in definition ["info" "title"])
             :version    (get-in definition ["info" "version"])
             :url        (get discovery "definition")
             :ui         (get discovery "ui")
             :definition (json/write-str definition)}

            ; incompatible definition
            {:status     "INCOMPATIBLE"
             :type       nil
             :name       nil
             :version    nil
             :url        (get discovery "definition")
             :ui         (get discovery "ui")
             :definition nil}))

        (catch Exception e
          (log/debug "Definition unavailable %s: %s" definition-url (str e))
          ; cannot fetch definition of discovery document
          {:status     (str "UNAVAILABLE")
           :type       nil
           :name       nil
           :version    nil
           :url        (get discovery "definition")
           :ui         (get discovery "ui")
           :definition nil})))

    (catch Exception e
      (log/warn "Undiscoverable service %s: %s" app-service-url (str e))
      ; cannot even load discovery document
      {; TODO make explicit status code for "NOT_REACHABLE" (no connection) and "NO_DISCOVERY" (404)
       :status     (str "UNDISCOVERABLE")
       :type       nil
       :name       nil
       :version    nil
       :url        nil
       :ui         nil
       :definition nil})))

(defn- crawl
  "One run to get API definitions of all applications."
  [configuration]
  (let [{:keys [kio-url storage-url]} configuration]
    (log/info "Starting new crawl run with %s..." kio-url)
    (try
      (doseq [app (fetch-apps kio-url)]
        (let [app-id (get app "id")
              app-service-url (get app "service_url")]
          (log/debug "Fetching update for %s from %s..." app-id app-service-url)
          (let [api-info (get-app-api-info app-service-url)]
            (log/debug "Storing result for %s: %s" app-id api-info)
            (try
              (client/put (add-path storage-url "/apis/" app-id)
                          {:content-type :json
                           :body         (json/write-str api-info)})
              (log/info "Updated %s." app-id)
              (catch Exception e
                (log/error e "Could not store result for %s in %s: %s" app-id storage-url (str e)))))))
      (catch Exception e
        (log/error e "Could not fetch apps %s." (str e))))))

(def-cron-component
  Jobs []

  (let [{:keys [every-ms initial-delay-ms]} configuration]

    (every every-ms (partial crawl configuration) pool :initial-delay initial-delay-ms :desc "API crawling")))