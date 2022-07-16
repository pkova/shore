(ns shore.main
  (:require [datomic.client.api :as d]
            [hato.client :as http]
            [clojure.data.json :as json]
            [clojure.string :as str]
            [clj-ob.ob :as ob]
            [datomic.ion.dev :as ion]
            [datomic.ion.cast :as cast]
            [datomic.ion.dev :as dev]
            [cognitect.aws.client.api :as aws]))

(def cfg {:server-type :ion
          :region "us-east-2"
          :system "shore"
          :endpoint "https://ja6vetvux9.execute-api.us-east-2.amazonaws.com"})

(def get-client (memoize (fn [] (d/client cfg))))

(def ec2 (aws/client {:api :ec2}))
(def route53 (aws/client {:api :route53}))

(defn safe-read-str [s]
  (try (json/read-str s)
       (catch Exception _ nil)))

(defn handler [{:keys [uri request-method body]}]
  (let [client (get-client)
        conn   (d/connect client {:db-name "shore"})
        db     (d/db conn)
        ticket (ffirst (d/q '[:find ?t
                              :in $ ?t
                              :where [_ :shore/ticket ?t]]
                            db
                            (get (safe-read-str (slurp body)) "ticket" "")))]
    (merge {:headers {"Access-Control-Allow-Origins" "https://shore.arvo.network"
                      "Access-Control-Allow-Methods" "POST"}}
     (cond
       (= :options request-method) {:status 200}
       (not= (= uri "/enter"))     {:status 404}
       (nil? ticket)               {:status 403}

       :else {:status 200
              :body
              (json/write-str {:url "https://halbex-palheb.arvo.network/~/login"
                               :code "rontud-bannus-wismeg-roswer"})}))))

(defn rand-patq []
  (-> (repeatedly 8 (fn [] (unchecked-byte (rand-int 256))))
      byte-array
      biginteger
      ob/biginteger->patq))

(defn add-tickets [n conn]
  (->> (repeatedly n (fn [] {:shore/ticket (rand-patq)}))
       (assoc {} :tx-data)
       (d/transact conn)))

(defn create-record [ip url]
  (aws/invoke
   route53
   {:op :ChangeResourceRecordSets
    :request
    {:HostedZoneId "Z00172511EBQKL94RJ8AW"
     :ChangeBatch
     {:Changes
      [{:Action "CREATE"
        :ResourceRecordSet
        {:Name url
         :Type "A"
         :TTL 300
         :ResourceRecords [{:Value ip}]}}]}}}))

;; (def conn (d/connect (get-client) {:db-name "shore"}))
