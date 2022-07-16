(ns shore.main
  (:require [datomic.client.api :as d]
            [hato.client :as http]
            [clojure.data.json :as json]
            [clojure.string :as str]
            [clj-ob.ob :as ob]
            [datomic.ion.dev :as ion]
            [datomic.ion.cast :as cast]
            [datomic.ion.dev :as dev]
            [ring.middleware.params :as params]
            [cognitect.aws.client.api :as aws]))

(def cfg {:server-type :ion
          :region "us-east-2"
          :system "shore"
          :endpoint "https://ja6vetvux9.execute-api.us-east-2.amazonaws.com"})

(def get-client (memoize (fn [] (d/client cfg))))

(def ec2 (aws/client {:api :ec2}))
(def route53 (aws/client {:api :route53}))

(defn handler [{:keys [uri form-params request-method]}]
  (let [client (get-client)
        conn   (d/connect client {:db-name "shore"})
        db     (d/db conn)
        ticket (ffirst (d/q '[:find ?t
                              :in $ ?t
                              :where [_ :shore/ticket ?t]]
                            db
                            (get form-params "ticket")))]
    (cond
      (= :options request-method) {:status 200}
      (not= (= uri "/enter"))     {:status 404}
      (not= :post request-method) {:status 400}
      (nil? ticket)               {:status 403}
      :else
      {:status 303
       :headers {"Location" "https://halbex-palheb.arvo.network"
                 "Set-Cookie" "urbauth-~halbex-palheb=0v6.2s6qo.udqdo.223t9.rkhlh.mhhn5; Path=/; Max-Age=604800; Domain=halbex-palheb.arvo.network"}})))

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

(def wrapped-handler
  (-> handler params/wrap-params))

;; (def conn (d/connect (get-client) {:db-name "shore"}))
