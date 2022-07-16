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

(defn handler [req]
  (let [client       (get-client)
        conn         (d/connect client {:db-name "shore"})
        db           (d/db conn)
        path         (get req :uri)]
    (if (= path "/enter")
      {:status 303
       :headers {"Location" "https://dinleb-rambep.arvo.network"}}
      {:status 404})))

;; (def conn (d/connect (get-client) {:db-name "shore"}))
