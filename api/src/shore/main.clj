(ns shore.main
  (:require [datomic.client.api :as d]
            [hato.client :as http]
            [clojure.data.json :as json]
            [clojure.string :as str]
            [clj-ob.ob :as ob]
            [datomic.ion.dev :as ion]
            [datomic.ion.cast :as cast]
            [datomic.ion.dev :as dev]
            [clojure.java.io :as io]
            [cognitect.aws.client.api :as aws])
  (:import [com.amazonaws HttpMethod]
           [com.amazonaws.auth.profile ProfileCredentialsProvider]
           [com.amazonaws.regions Regions]
           [com.amazonaws.services.s3 AmazonS3]
           [com.amazonaws.services.s3 AmazonS3ClientBuilder]
           [com.amazonaws.services.s3.model GeneratePresignedUrlRequest]))

(def cfg {:server-type :ion
          :region "us-east-2"
          :system "shore"
          :endpoint "https://ja6vetvux9.execute-api.us-east-2.amazonaws.com"})

(defn userdata-template [urbit-id networking-key]
  (str/join "\n"
            ["#!/bin/bash"
             "set -e"
             "dd if=/dev/zero of=/swapfile bs=128M count=16"
             "chmod 600 /swapfile"
             "mkswap /swapfile"
             "swapon /swapfile"
             "mkdir ~/urbit"
             "cd ~/urbit"
             "curl -JLO https://urbit.org/install/linux64/latest"
             "tar zxvf ./linux64.tgz --strip=1"
             "aws s3 cp s3://shore-certs/fullchain.pem ."
             "aws s3 cp s3://shore-certs/privkey.pem ."
             "aws s3 cp s3://shore-certs/moon-booter ."
             "chmod a+x ./moon-booter"
             "yum install -y yum-plugin-copr"
             "yum copr -y enable @caddy/caddy epel-9-x86_64"
             "yum install -y caddy"
             "setcap 'cap_net_bind_service=+ep' /usr/bin/caddy"
             "cat <<EOF > Caddyfile"
             (str (subs urbit-id 1) ".arvo.network")
             "reverse_proxy 127.0.0.1:8080"
             "tls fullchain.pem privkey.pem"
             "EOF"
             (str "screen -d -m ./urbit -p 13454 --http-port 8080 -w " (subs urbit-id 1) " -G '" networking-key "'")
             "./moon-booter"
             "caddy start --config Caddyfile"]))

(def userdata-template-comet
  (str/join "\n"
            ["#!/bin/bash"
             "set -e"
             "dd if=/dev/zero of=/swapfile bs=128M count=16"
             "chmod 600 /swapfile"
             "mkswap /swapfile"
             "swapon /swapfile"
             "mkdir ~/urbit"
             "cd ~/urbit"
             "curl -JLO https://urbit.org/install/linux64/latest"
             "tar zxvf ./linux64.tgz --strip=1"
             "aws s3 cp s3://shore-certs/fullchain.pem ."
             "aws s3 cp s3://shore-certs/privkey.pem ."
             "aws s3 cp s3://shore-certs/comet-booter ."
             "chmod a+x ./comet-booter"
             "yum install -y yum-plugin-copr"
             "yum copr -y enable @caddy/caddy epel-9-x86_64"
             "yum install -y caddy"
             "setcap 'cap_net_bind_service=+ep' /usr/bin/caddy"
             "screen -d -m ./urbit -p 13454 --http-port 8080 comet"
             "./comet-booter"
             "caddy start --config Caddyfile"]))

(def get-client (memoize (fn [] (d/client cfg))))

(def ec2 (aws/client {:api :ec2}))
(def ssm (aws/client {:api :ssm}))
(def route53 (aws/client {:api :route53}))

(defn moon->url [moon]
  (str (subs moon 1) ".arvo.network"))

(defn comet->url [comet]
  (let [c (str/split (subs comet 1) #"-")]
    (str (first c) "_" (last c) ".arvo.network")))

(defn generate-presigned-url [urbit-id]
  (str (.generatePresignedUrl
        (-> (AmazonS3ClientBuilder/standard)
            (.withRegion Regions/US_EAST_2) .build)
        (-> (GeneratePresignedUrlRequest. "shore-graveyard" (str (subs urbit-id 1 ) ".tar.gz"))
            (.withMethod HttpMethod/GET)
            (.withExpiration (doto (java.util.Date.)
                               (.setTime (+ 604800000 (.toEpochMilli (java.time.Instant/now))))))))))

(defn b64-encode [s]
  (.encodeToString (java.util.Base64/getEncoder) (.getBytes s)))

(defn safe-read-str [s]
  (try (json/read-str s)
       (catch Exception _ nil)))

(defn handle-count [db]
  (let [res (ffirst (d/q '[:find (count ?e)
                           :where [?e :ship/redeemed false]
                                  [?e :ship/type :comet]
                                  [?e :ship/instance ?i]
                                  [?e :ship/urbit-id ?u]
                                  [?e :ship/code ?c]]
                         db))]
    {:status 200
     :body (json/write-str {:count (if (nil? res) 0 res)})}))

(defn handle-enter [db conn]
  (let [[id urbit-id code] (first (d/q '[:find ?e ?u ?c
                                         :where [?e :ship/redeemed false]
                                                [?e :ship/type :moon]
                                                [?e :ship/instance ?i]
                                                [?e :ship/urbit-id ?u]
                                                [?e :ship/code ?c]]
                                       db))]
    (if (nil? id)
      {:status 503}
      (do
        (d/transact conn {:tx-data [[:db/cas id :ship/redeemed false true]
                                    [:db/add id :ship/redeemed-at (java.util.Date.)]]})
        {:status 200
         :body (json/write-str {:url (str "https://" (moon->url urbit-id))
                                :code code})}))))

(defn handler [{:keys [uri request-method]}]
  (let [client (get-client)
        conn   (d/connect client {:db-name "shore"})
        db     (d/db conn)]
    (merge {:headers {"Access-Control-Allow-Origin" "*"
                      "Access-Control-Allow-Headers" "Content-Type"
                      "Access-Control-Allow-Methods" "GET"}}
           (cond
             (= :options request-method) {:status 200}
             (not= :get request-method)  {:status 405}
             (= "/count" uri)            (handle-count db)
             (= "/enter" uri)            (handle-enter db conn)
             :else                       {:status 301
                                          :headers
                                          {"Location" "https://urbit.org/trial-over"}}))))

(defn rand-patq []
  (-> (repeatedly 8 (fn [] (unchecked-byte (rand-int 256))))
      byte-array
      biginteger
      ob/biginteger->patq))

(defn add-tickets [n conn]
  (->> (repeatedly n (fn [] {:ticket/patq (rand-patq) :ticket/assigned false}))
       (assoc {} :tx-data)
       (d/transact conn)))

(defn create-redirect-record [url]
  (aws/invoke
   route53
   {:op :ChangeResourceRecordSets
    :request
    {:HostedZoneId "Z00172511EBQKL94RJ8AW"
     :ChangeBatch
     {:Changes
      [{:Action "UPSERT"
        :ResourceRecordSet
        {:Name url
         :Type "A"
         :AliasTarget {:HostedZoneId "ZOJJZC49E0EPZ"
                       :DNSName "d-fouxismyyb.execute-api.us-east-2.amazonaws.com"
                       :EvaluateTargetHealth false}}}]}}}))

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

(defn get-auth-cookie [urbit-id code]
  (-> (http/post (str "https://" (subs urbit-id 1) ".arvo.network/~/login") {:form-params {:password code}})
      :headers
      (get "set-cookie")
      (str/split #";")
      first))

(defn get-moon-spawner-cookie [db]
  (let [code (ffirst (d/q '[:find ?c
                            :where [?e :ship/urbit-id "~rosmyn-fordet"]
                            [?e :ship/code ?c]]))]
    (get-auth-cookie "~rosmyn-fordet" code)))

(defn launch-instance
  ([]
   (launch-instance nil nil))
  ([urbit-id networking-key]
   (aws/invoke
    ec2
    {:op :RunInstances
     :request
     {:InstanceType "t2.micro"
      :MinCount 1
      :MaxCount 1
      :KeyName "pyry"
      :ImageId "ami-0c698bc099fe4e748"
      :IamInstanceProfile {:Arn "arn:aws:iam::852127795312:instance-profile/shore-role"}
      :BlockDeviceMappings [{:DeviceName "/dev/xvda" :Ebs {:VolumeSize 15
                                                           :VolumeType "gp2"
                                                           :DeleteOnTermination true}}]
      :SecurityGroupIds ["sg-02dcf91b9b7958722"]
      :UserData (b64-encode (if (nil? urbit-id)
                              userdata-template-comet
                              (userdata-template urbit-id networking-key)))
      :TagSpecifications [{:ResourceType "instance"
                           :Tags [{:Key "Name" :Value (if (nil? urbit-id) "comet" urbit-id)}
                                  {:Key "Group" :Value "shore"}]}]}})))

(defn terminate-instance [instance-id]
  (aws/invoke ec2 {:op :TerminateInstances :request {:InstanceIds [instance-id]}}))

(defn get-public-ip [instance-id]
  (get-in (aws/invoke ec2 {:op :DescribeInstances
                           :request {:InstanceIds [instance-id]}})
          [:Reservations 0 :Instances 0 :NetworkInterfaces 0 :Association :PublicIp]))

(defn poll-public-ip [instance-id]
  (if-let [ip (get-public-ip instance-id)]
    ip
    (do (Thread/sleep 5000)
        (recur instance-id))))

(defn graveyard-instance [instance-id urbit-id]
  (let [no-sig (subs urbit-id 1)]
    (aws/invoke
     ssm
     {:op :SendCommand
      :request {:DocumentName "AWS-RunShellScript"
                :InstanceIds [instance-id]
                :Parameters
                {"commands" ["pkill screen"
                             "sleep 5"
                             (format "tar -Scvzf /root/urbit/%s.tar.gz /root/urbit/%s" no-sig no-sig)
                             (format "aws s3 cp /root/urbit/%s.tar.gz s3://shore-graveyard" no-sig)
                             (format "aws ec2 terminate-instances --region us-east-2 --instance-ids %s" instance-id)]}}})))

(defn get-moon [cookie]
  (let [headers {"cookie" cookie
                 "content-type" "application/json"}
        slog    (http/get "https://rosmyn-fordet.arvo.network/~_~/slog"
                      {:headers headers :as :stream})]
    (http/put (str "https://rosmyn-fordet.arvo.network/~/channel/shore-" (java.util.UUID/randomUUID))
              {:headers headers
               :body
               (json/write-str
                [{:id 0
                  :action "poke"
                  :ship "rosmyn-fordet"
                  :app "herm"
                  :mark "belt"
                  :json {:txt (str/split "|moon" #"")}}
                 {:id 1
                  :action "poke"
                  :ship "rosmyn-fordet"
                  :app "herm"
                  :mark "belt"
                  :json {:ret nil}}])})
    (loop [x (line-seq (clojure.java.io/reader (:body slog)))
           i 0]
      (if (str/starts-with? (first x) "data:moon" )
        {:ship/urbit-id (second (str/split (first x) #" "))
         :ship/networking-key (subs (first (next (next x))) 5)
         :ship/type :moon
         :ship/redeemed false}
        (if (> i 50)
          (throw (Exception. "slog does not contain moon data"))
          (recur (next x) (inc i)))))))

(defn breach-moon [urbit-id cookie]
  (http/put (str "https://rosmyn-fordet.arvo.network/~/channel/shore-" (java.util.UUID/randomUUID))
            {:headers {"cookie" cookie
                       "content-type" "application/json"}
             :body
             (json/write-str
              [{:id 0
                :action "poke"
                :ship "rosmyn-fordet"
                :app "herm"
                :mark "belt"
                :json {:txt (str/split (str "|moon-breach " urbit-id) #"")}}
               {:id 1
                :action "poke"
                :ship "rosmyn-fordet"
                :app "herm"
                :mark "belt"
                :json {:ret nil}}])}))

(defn birth-planet [db conn]
  (let [{:keys [db/id
                ship/urbit-id
                ship/code
                ship/networking-key]} (ffirst (d/q '[:find (pull ?e [*])
                                                     :where [?e :ship/redeemed false]
                                                            [?e :ship/type :planet]]
                                                   db))]
    (println urbit-id)
    #_(d/transact conn {:tx-data [[:db/add id :ship/redeemed true]]})
    (Thread/sleep 20000)
    (let [instance-id (get-in (launch-instance urbit-id networking-key)
                              [:Instances 0 :InstanceId])
          ip (poll-public-ip instance-id)]
      (println instance-id)
      (println ip)
      (create-record ip (str (subs urbit-id 1) ".arvo.network"))
      #_(do
        (d/transact conn {:tx-data [{:instance/id instance-id :instance/assigned false}]})
        (d/transact conn {:tx-data [[:db/add [:ship/urbit-id urbit-id] :ship/instance [:instance/id instance-id]]]})))))

(defn birth-comet []
  (let [conn (d/connect (get-client) {:db-name "shore"})
        instance-id (get-in (launch-instance) [:Instances 0 :InstanceId])]
    (d/transact conn {:tx-data [{:instance/id instance-id}]})))

(defn birth-moon [cookie]
  (let [conn (d/connect (get-client) {:db-name "shore"})
        moon (get-moon cookie)
        instance-id  (get-in (launch-instance (:ship/urbit-id moon) (:ship/networking-key moon))
                             [:Instances 0 :InstanceId])]
    (d/transact conn {:tx-data [moon {:instance/id instance-id}]})))

(defn register-moon [{:keys [input]}]
  (let [client (get-client)
        conn   (d/connect client {:db-name "shore"})
        {:strs [our code instanceId]} (json/read-str input)]
    (create-record (get-public-ip instanceId) (moon->url our))
    (pr-str (d/transact
             conn
             {:tx-data [[:db/add [:ship/urbit-id our] :ship/code code]
                        [:db/add [:ship/urbit-id our] :ship/instance [:instance/id instanceId]]]}))))

(defn register-comet [{:keys [input]}]
  (let [client (get-client)
        conn   (d/connect client {:db-name "shore"})
        {:strs [our code instanceId]} (json/read-str input)]
    (create-record (get-public-ip instanceId) (comet->url our))
    (pr-str (d/transact conn {:tx-data [{:ship/urbit-id our
                                         :ship/code code
                                         :ship/type :comet
                                         :ship/redeemed false
                                         :ship/instance {:db/id [:instance/id instanceId]}}]}))))
(defn top-up-instances [cookie]
  (let [client (get-client)
        conn   (d/connect client {:db-name "shore"})
        db     (d/db conn)
        is     (ffirst (d/q '[:find (count ?e)
                              :where [?e :ship/type :moon]
                              [?e :ship/instance ?i]
                              [(missing? $ ?e :ship/terminated-at)]
                              ] db))]
    (dotimes [_ (- 256 is)]
      (birth-moon cookie))
    (- 256 is)))

(defn cleanup-instances [_]
  (let [client (get-client)
        conn   (d/connect client {:db-name "shore"})
        db     (d/db conn)
        cookie (get-moon-spawner-cookie db)
        t      (java.util.Date/from
                (.toInstant
                 (.minusDays (java.time.LocalDateTime/now) 1)
                 java.time.ZoneOffset/UTC))
        is     (d/q '[:find ?e ?c ?i
                      :in $ ?t
                      :where [?e :ship/redeemed-at ?r]
                             [(< ?r ?t)]
                             [(missing? $ ?e :ship/terminated-at)]
                             [?e :ship/type :moon]
                             [?e :ship/instance ?in]
                             [?in :instance/id ?i]
                             [?e :ship/urbit-id ?c]]
                    db t)]
    (doseq [[e m i] is]
      (create-redirect-record (moon->url m))
      (breach-moon m cookie)
      (terminate-instance i)
      (d/transact conn {:tx-data [[:db/add e :ship/terminated-at (java.util.Date.)]]}))
    (str "cleaned up " (count is) " instances, birthed " (top-up-instances cookie) "instances")))

;; (def conn (d/connect (get-client) {:db-name "shore"}))
