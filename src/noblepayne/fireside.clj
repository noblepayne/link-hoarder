(ns noblepayne.fireside
  (:gen-class)
  (:require [hato.client :as http]
            [hickory.core :as hickory]
            [hickory.select :as hs]))

;; Setup
(set! *warn-on-reflection* true)

;; Env Vars
(def FIRESIDE-BASE-URL (System/getenv "FIRESIDE_BASE_URL"))
(def FIRESIDE-USER (System/getenv "FIRESIDE_USER"))
(def FIRESIDE-SECRET (System/getenv "FIRESIDE_SECRET"))

(defn http-client []
  (http/build-http-client
   {:connect-timeout 30000
    :redirect-policy :always
    :cookie-policy :original-server}))

(defn login-to-fireside [client]
  (let [login-page (-> (str FIRESIDE-BASE-URL "/login")
                       (http/get {:http-client client})
                       :body
                       hickory/parse
                       hickory/as-hickory)
        [login-form] (->> login-page
                          (hs/select (hs/class :form-signin)))
        form-action (-> login-form :attrs :action)
        inputs (hs/select (hs/tag :input) login-form)
        parsed-inputs (into {} (map (comp (juxt :name :value) :attrs)
                                    inputs))
        form-params (assoc parsed-inputs
                           "email" FIRESIDE-USER
                           "password" FIRESIDE-SECRET)]
    (http/post form-action {:http-client client :form-params form-params})))
  
(defn add-link [{:keys [client podcast episode-guid title url quote]}]
  (let [new-url (str FIRESIDE-BASE-URL "/podcasts/" podcast
                     "/episodes/" episode-guid "/links/new")
        new-url-page (-> new-url
                         (http/get {:http-client client})
                         :body
                         hickory/parse
                         hickory/as-hickory)
        [links-form] (hs/select (hs/and (hs/id "new_link"))
                                   new-url-page)
        form-action (str FIRESIDE-BASE-URL (-> links-form
                                               :attrs
                                               :action
                                               java.net.URI/create
                                               .getPath))
        links-inputs (hs/select (hs/tag :input) links-form)
        parsed-inputs (into {} (map (comp (juxt :name :value) :attrs) links-inputs))
        form-params (assoc parsed-inputs
                           "link[title]" title
                           "link[url]" url
                           "link[excerpt]" quote)]
    (http/post form-action {:http-client client :form-params form-params})))


(comment
  (def c (http-client))
  (login-to-fireside c)
  (add-link
   {:client c
    :podcast "linuxunplugged"
    :episode-guid "89cf45f9-394a-482e-8ef9-f2b530188274"
    :title "test title"
    :url "http://test.url"
    :quote "test quote"}))