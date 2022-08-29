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
             "yum install -y yum-plugin-copr"
             "yum copr -y enable @caddy/caddy epel-9-x86_64"
             "yum install -y caddy"
             "setcap 'cap_net_bind_service=+ep' /usr/bin/caddy"
             "cat <<EOF > Caddyfile"
             (str (subs urbit-id 1) ".arvo.network")
             "reverse_proxy 127.0.0.1:8080"
             "tls fullchain.pem privkey.pem"
             "EOF"
             (str "screen -d -m ./urbit -p 13454 -w " (subs urbit-id 1) " -G '" networking-key "'")
             "caddy start --config Caddyfile"]))

(def get-client (memoize (fn [] (d/client cfg))))

(def ec2 (aws/client {:api :ec2}))
(def ssm (aws/client {:api :ssm}))
(def route53 (aws/client {:api :route53}))

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

(defn handler [{:keys [uri request-method body]}]
  (let [client (get-client)
        conn   (d/connect client {:db-name "shore"})
        db     (d/db conn)]
    (merge {:headers {"Access-Control-Allow-Origin" "*"
                      "Access-Control-Allow-Headers" "Content-Type"
                      "Access-Control-Allow-Methods" "GET"}}
     (cond
       (= :options request-method) {:status 200}
       (not= (= uri "/enter"))     {:status 404}
       :else
       (let [[urbit-id code] (first (d/q '[:find ?u ?c
                                           :where [?e :ship/redeemed false]
                                                  [?e :ship/instance ?i]
                                                  [?e :ship/urbit-id ?u]
                                                  [?e :ship/code ?c]
                                           ] db))]
           {:status 200
            :body
            (json/write-str {:url (format "https://%s.arvo.network" (subs urbit-id 1))
                             :code code})})))))

(defn rand-patq []
  (-> (repeatedly 8 (fn [] (unchecked-byte (rand-int 256))))
      byte-array
      biginteger
      ob/biginteger->patq))

(defn add-tickets [n conn]
  (->> (repeatedly n (fn [] {:ticket/patq (rand-patq) :ticket/assigned false}))
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

(defn get-auth-cookie [ip code]
  (-> (http/post (str "http://" ip "/~/login") {:form-params {:password code}})
      :headers
      (get "set-cookie")
      (str/split #";")
      first))

(defn launch-instance [urbit-id networking-key]
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
     :UserData (b64-encode (userdata-template urbit-id networking-key))
     :TagSpecifications [{:ResourceType "instance"
                          :Tags [{:Key "Name" :Value urbit-id}
                                 {:Key "Group" :Value "shore"}]}]}}))

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

(defn birth-instance [db conn]
  (let [{:keys [db/id
                ship/urbit-id
                ship/code
                ship/networking-key]} (ffirst (d/q '[:find (pull ?e [*])
                                                     :where [?e :ship/redeemed false]] db))]
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

;; (def conn (d/connect (get-client) {:db-name "shore"}))
