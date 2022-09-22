(ns shore.schema)

(def ticket-schema
  [{:db/ident :ticket/patq
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/one
    :db/unique :db.unique/identity
    :db/doc "@q formatted ticket for Shore, completely separate from L2 Bridge Activation ticket."}
   {:db/ident :ticket/redeemed-at
    :db/valueType :db.type/instant
    :db/cardinality :db.cardinality/one
    :db/doc "The timestamp of when the Shore ticket was redeemed."}])

(def user-schema
  [{:db/ident :user/email
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/one
    :db/unique :db.unique/identity
    :db/doc "Email of the user."}
   {:db/ident :user/ticket
    :db/valueType :db.type/ref
    :db/cardinality :db.cardinality/one
    :db/doc "Reference to the ticket that the user redeemed"}
   {:db/ident :user/ship
    :db/valueType :db.type/ref
    :db/cardinality :db.cardinality/one
    :db/doc "Reference to the ship the user was assigned"}])

(def ship-schema
  [{:db/ident :ship/urbit-id
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/one
    :db/unique :db.unique/identity
    :db/doc "@p for the ship, L2 planet or comet."}
   {:db/ident :ship/type
    :db/valueType :db.type/keyword
    :db/cardinality :db.cardinality/one
    :db/doc "Type of ship, :planet, :comet or :moon."}
   {:db/ident :ship/activation-ticket
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/one
    :db/doc "L2 Activation master ticket."}
   {:db/ident :ship/networking-key
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/one
    :db/doc "The Urbit networking key for booting."}
   {:db/ident :ship/code
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/one
    :db/doc "Urbit access +code."}
   {:db/ident :ship/redeemed
    :db/valueType :db.type/boolean
    :db/cardinality :db.cardinality/one
    :db/doc "Whether the ship has been redeemed by a user."}
   {:db/ident :ship/redeemed-at
    :db/valueType :db.type/instant
    :db/cardinality :db.cardinality/one
    :db/doc "The timestamp when the ship was redeemed."}
   {:db/ident :ship/terminated-at
    :db/valueType :db.type/instant
    :db/cardinality :db.cardinality/one
    :db/doc "The timestamp when the ship was terminated."}
   {:db/ident :ship/instance
    :db/valueType :db.type/ref
    :db/cardinality :db.cardinality/one
    :db/doc "Reference to the instance running the ship, if any."}])

(def instance-schema
  [{:db/ident :instance/id
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/one
    :db/unique :db.unique/identity
    :db/doc "The AWS provided instance ID."}])
