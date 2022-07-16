(ns shore.schema)

(def shore-schema
  [{:db/ident :shore/ticket
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/one
    :db/unique :db.unique/identity
    :db/doc "@q formatted ticket for Shore, completely separate from L2 Bridge Activation ticket."}])

(def user-schema
  [{:db/ident :user/email
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/one
    :db/unique :db.unique/identity
    :db/doc "Email of the user."}
   {:db/ident :user/ship
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/one
    :db/doc "@p for the ship, always a L2 planet."}
   {:db/ident :user/activation-ticket
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/one
    :db/doc "L2 Activation ticket."}
   {:db/ident :user/code
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/one
    :db/doc "L2 Activation ticket."}
   {:db/ident :user/cookie
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/one
    :db/doc "Authenticated login cookie for the ship."}])
