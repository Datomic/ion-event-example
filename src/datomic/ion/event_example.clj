;; Copyright (c) Cognitect, Inc.
;; All rights reserved.
;;
;; Licensed under the Apache License, Version 2.0 (the "License");
;; you may not use this file except in compliance with the License.
;; You may obtain a copy of the License at
;;
;;      http://www.apache.org/licenses/LICENSE-2.0
;;
;; Unless required by applicable law or agreed to in writing, software
;; distributed under the License is distributed on an "AS-IS" BASIS,
;; WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
;; See the License for the specific language governing permissions and
;; limitations under the License.


(ns datomic.ion.event-example
  (:import [java.nio ByteBuffer])
  (:require
   [clojure.core.async :refer (<!!)]
   [clojure.data.json :as json]
   [clojure.edn :as edn]
   [clojure.java.io :as io]
   [clojure.instant :as instant]
   [clojure.pprint :as pp]
   [clojure.string :as str]
   [cognitect.http-client :as http]
   [datomic.client.api :as d]
   [datomic.ion :as ion]
   [datomic.ion.cast :as cast]
   [datomic.ion.lambda.api-gateway :as apigw]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; App params
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn fail
  [k]
  (throw (RuntimeException. (str "Unable to get a value for " k ", see https://docs.datomic.com/cloud/ions/ions-reference.html#parameters."))))

(def get-params
  "Returns the SSM params under /datomic-shared/(env)/(app-name)/, where

env       value of get-env :env
app-name  value of get-app-info :app-name"
  (memoize
   #(let [app (or (get (ion/get-app-info) :app-name) (fail :app-name))
          env (or (get (ion/get-env) :env) (fail :env))]
      (ion/get-params {:path (str "/datomic-shared/" (name env) "/" app "/")}))))

(defn get-param
  [k]
  (or (get (get-params) k) (fail k)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Get a connection. Note the :server-type :ion, see also
;; https://docs.datomic.com/cloud/ions/ions-reference.html#server-type-ion
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(def get-client
  (memoize #(d/client 
             {:server-type :ion
              :region "us-east-1"
              :system "stu-8"
              :query-group "stu-8"
              :endpoint "http://entry.stu-8.us-east-1.datomic.net:8182/"
              :proxy-port 8182})))
(def get-http-client (memoize #(http/create {})))
(def get-conn (memoize #(d/connect (get-client) {:db-name (get-param "db-name")})))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Create the example database. Note that the schema data is keyed
;; by an ident, so you can add new bits of schema later.
;; See http://blog.datomic.com/2017/01/the-ten-rules-of-schema-growth.html
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn schemas
  "Returns a map from schema subset identifier to tx-data"
  []
  (-> "datomic/ion/event_example/schema.edn" io/resource slurp edn/read-string))
(defn ident-exists? [db ident] (not (empty? (d/pull db '[:db/ident] ident))))
(defn create-example-database []
  (let [client (get-client)]
    (d/create-database client {:db-name (get-param "db-name")})
    (let [conn (d/connect client {:db-name (get-param "db-name")})]
      (doseq [[id tx-data] (schemas)]
        (when-not (ident-exists? (d/db conn) id)
          (d/transact conn {:tx-data tx-data})))
      conn)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; HTTP post to slack. I needed only to make only a single call
;; to the Slack API, so this was easier than depending on a lib.
;; For the full Slack API in Clojure see e.g.
;; https://github.com/julienXX/clj-slack
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn ->bbuf [^String s] (-> s .getBytes ByteBuffer/wrap))
(defn ->str
  [^ByteBuffer bb]
  (let [n (.remaining bb)
        bs (byte-array n)]
    (.get bb bs)
    (String. bs)))

(defn post-slack-message [channel text]
  (cast/dev {:msg "PostingToSlack" ::channel channel ::text text})
  (let [token (get-param "bot-token")
        conn {:api-url "https://slack.com/api" :token token}
        result (<!! (http/submit (get-http-client)
                                 {:server-name "slack.com"
                                  :server-port 443
                                  :headers {"content-type" "application/json"
                                            "authorization" (str "Bearer " token)}
                                  :uri "/api/chat.postMessage"
                                  :body (-> {:channel channel
                                             :text text}
                                            json/write-str
                                            ->bbuf)
                                  :request-method :post
                                  :scheme :https}))]
    (update result :body #(when % (->str %)))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Convert from AWS event format JSON to information, and pluck
;; out the parts we want to remember as transaction data.
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn keywordize [s] (-> s (str/replace " " "") keyword))

(def code-deploy-event-rules
  "Map from a path in a JSON map to the Clojure ident for tx-data.
When the value is a map, the function named is used to convert the
JSON. See event->tx."
  {["detail" "region"] {:deploy/region keywordize}
   ["detail" "deploymentId"] :deploy/id
   ["detail" "application"] :deploy/application
   ["detail" "state"] {:deploy/state keywordize}
   ["detail" "deploymentGroup"] :deploy/group
   ["time"] {:deploy/time instant/read-instant-date}})

(def code-deploy-event-refs
  "Map from reference attributes to unique identity attrs
for that reference type. See add-refs"
  {:deploy/application :application/name
   :deploy/group :group/name})

(defn key-and-transform
  "Interprets the value of an event->tx rule, returning a key and
a transform function."
  [x]
  (if (keyword? x) [x identity] (first x)))

(defn event->tx
  "Convert AutoScaling event data (JSON string) to tx-data, plucking
out keys we care about per rules"
  [rules json]
  (let [data (json/read-str json)]
    (reduce
     (fn [result [path x]]
       (let [[k xf] (key-and-transform x)]
         (if-let [v (get-in data path)]
           (assoc result k (xf v))
           result)))
     {}
     rules)))

(defn add-refs
  "Given event tx-data map, return a vector of tx-data that
includes the event plus data to ensure any unique entities
that the event referenaces"
  [refs event]
  (into
   [event]
   (map (fn [[attr unique-attr]]
          (let [v (get event attr)]
            {:db/id v
             unique-attr v}))
        refs)))

(defn code-block
  "Markdown format a code block for the Slack API"
  [s]
  (format "```%s```" s))

(defn event-handler
  "Ion that responds to AWS CodeDeploy events. Transacts event
info into the database, and posts a notification in Slack."
  [{:keys [input]}]
  (cast/event {:msg "CodeDeployEvent" ::json input})
  (cast/metric {:name :CodeDeployEvent :value 1 :units :count})
  (let [conn (get-conn)
        data (->> input
                  (event->tx code-deploy-event-rules)
                  (add-refs code-deploy-event-refs))
        slack-channel (get-param "channel")]
    (d/transact conn {:tx-data data})
    (post-slack-message slack-channel (-> data pr-str code-block))
    "handled"))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Respond to Slack mentions with a dump of recent deploys.
;; This is hard coded and could be extended to e.g. respond
;; differently to different Slack events.
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn hours-ago
  [h]
  (java.util.Date. (- (System/currentTimeMillis) (* h 60 60 1000))))

(defn recent-deploys
  "Return last n CodeDeploy deploys that have happenend since
'since'."
  [db since n]
  (->> (d/q '[:find (pull ?e [*
                              {:deploy/application [:application/name]}
                              {:deploy/group [:group/name]}])
              :in $ ?since
              :where
              [?e :deploy/time ?time]
              [(<= ?since ?time)]]
            db
            since)
       (map first)
       (sort-by (fn [{:keys [:deploy/time]}] (- (.getTime time))))
       (take n)))

(defn tableize-deploys
  ""
  [deploys]
  (with-out-str
    (->> deploys
         (map (fn [dep] {:id (:deploy/id dep)
                         :state (:deploy/state dep)
                         :time (:deploy/time dep)
                         :group (-> dep :deploy/group :group/name)
                         :app (-> dep :deploy/application :application/name)}))
         (pp/print-table [:time :group :app :state :id]))))

(defn deploys-table
  ""
  []
  (let [deploys (recent-deploys (d/db (get-conn)) (hours-ago 3) 10)]
    (if (seq deploys)
      (-> deploys tableize-deploys code-block)
      (code-block "no recent deploys"))))

(defn slack-event-handler*
  "Web service ion that responds to slack notifications by printing
an org-mode table of recent deploys."
  [{:keys [headers body]}]
  (try
   (let [json (-> body io/reader (json/read :key-fn keyword))
         db (d/db (get-conn))
         slack-channel (get-param "channel")
         verified? (= (get json :token)
                      (get-param "verification-token"))]
     (if verified?
       (if-let [challenge (get json :challenge)]
         {:status 200 :headers {} :body challenge}
         (let [posted (post-slack-message slack-channel (deploys-table))]
           {:status 200 :headers {}}))
       {:status 503 :headers {}}))
   (catch Throwable t
     (cast/alert {:msg "SlackHandlerFailed" :ex t}))))

(def slack-event-handler (apigw/ionize slack-event-handler*))
