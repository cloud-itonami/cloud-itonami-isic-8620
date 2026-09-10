(ns clinic.render-html
  "Build-time operator-console renderer.

  This namespace RUNS the actor -- `clinic.operation/build` compiled as a
  langgraph-clj StateGraph, driven with `langgraph.graph/run*` over the
  real `clinic.store/seed-db` seed -- and renders the resulting ledger,
  registers and governor verdicts to `docs/samples/operator-console.html`.

  NOTHING on the page is authored by hand. Every encounter id, patient
  name, jurisdiction, verdict, violation rule, confidence and record
  number printed below is read back out of the store AFTER the run, or
  out of the run's own returned graph state. This is a CLINICAL domain:
  if a value cannot be derived from an actual execution over the seeded
  encounter set, it is not printed at all.

  Build-time invariant (`-main` throws, and writes no file):

    - the run must produce at least one HARD governor hold -- a genuine,
      un-overridable refusal by `clinic.governor`. A console that shows
      only green rows would be evidence of nothing; if the governor
      stops refusing, this build must fail rather than publish a page
      claiming a censor that no longer censors.
    - the generated body must survive `structural-problems` (tag
      balance, no unresolved template tokens).

  Hold classification -- read this before trusting the counts:

    Three DIFFERENT things end a run in `:disposition :hold`, and two of
    them share ONE fact type. Classifying on `:violations` alone gets all
    three wrong:

      1. a HARD governor refusal      `:t :governor-hold`, no :phase-reason
      2. a rollout PHASE gate         `:t :governor-hold` WITH :phase-reason
                                      -- `clinic.operation`'s :decide node
                                      reuses `governor/hold-fact` for a
                                      phase-disabled op, so the fact wears
                                      the governor's name while carrying an
                                      EMPTY :violations vector. This is not
                                      the governor refusing anything.
      3. a human approver rejecting   `:t :approval-rejected` -- and this
                                      one DOES carry a violation
                                      (`{:rule :approver-rejected}`),
                                      synthesised by :request-approval.
                                      A `(seq (:violations f))` test counts
                                      it as a governor refusal. It is not.

    So `classify-hold` dispatches on the FACT TYPE first, and only then
    uses :phase-reason to split the governor's own fact type in two.

  Approver attribution is DERIVED, never asserted: `approver-attribution`
  re-scans the persisted registers and ledger for approver-shaped keys
  holding the approver id that was actually used to resume the run. It
  reports what it finds. If someone changes `clinic.operation/commit-record`
  so more (or less) of the approver survives, the page follows -- there is
  no hard-coded claim about this repo's behaviour to go stale.

    CAUTION for anyone extending this: ledger facts carry `:actor`, which
    is `(:actor-id context)` -- the EXECUTING actor, not the approver.
    Reading `:actor` as attribution looks right whenever the operator and
    the approver happen to share an id. This build deliberately gives the
    actor and every approver DISTINCT ids so that confusion cannot pass.

  Usage:
    clojure -M:dev:render-html                 ; -> docs/samples/operator-console.html
    clojure -M:dev:render-html <out.html>      ; -> explicit path (determinism runs)"
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [kotoba.lang.text :as str]
            [clinic.facts :as facts]
            [clinic.operation :as op]
            [clinic.store :as store]
            [langgraph.graph :as g]))

(def ^:private default-out "docs/samples/operator-console.html")

;; ============================ scenarios ============================

(def actor-context
  "The executing actor. Its id is deliberately UNLIKE every approver id
  below -- see the ns docstring's caution about `:actor`."
  {:actor-id "clinic-actor-1" :actor-role :licensed-physician})

(def scenarios
  "Every scenario is a real request put through the compiled graph. The
  `:expect` key is documentation of intent only -- it is never printed as
  a result, and never substitutes for one. `-main` compares it to what
  actually happened and prints the mismatches."
  [{:id "s01" :phase 3
    :title "通常の診療記録更新 (auto-commit)"
    :note "phase 3 は :encounter/intake だけを自動コミット可能にしている唯一の op。"
    :request {:op :encounter/intake :subject "encounter-1"
              :patch {:id "encounter-1" :patient "Sakura Tanaka"}}
    :expect :commit}

   {:id "s02" :phase 1
    :title "phase 1 の診療記録更新 (rollout gate が承認を要求)"
    :note "governor は clean。承認を要求したのは phase gate (:phase-approval)。"
    :request {:op :encounter/intake :subject "encounter-3"
              :patch {:id "encounter-3" :patient "鈴木一郎"}}
    :approval {:status :approved :by "dr-ito-intake"}
    :expect :commit}

   {:id "s03" :phase 3
    :title "法域ライセンス評価 encounter-1 (人が承認)"
    :note "JPN は clinic.facts に公式 spec-basis がある法域。"
    :request {:op :jurisdiction/assess :subject "encounter-1"}
    :approval {:status :approved :by "dr-kudo-jurisdiction"}
    :expect :commit}

   {:id "s04" :phase 3
    :title "臨床医免許スクリーニング encounter-1 (人が承認)"
    :request {:op :credential/screen :subject "encounter-1"}
    :approval {:status :approved :by "dr-mori-credential"}
    :expect :commit}

   {:id "s05" :phase 3
    :title "治療実施 encounter-1 (actuation -- 常に人の判断)"
    :note "governor の high-stakes gate と phase gate が独立に同じ結論を出す。"
    :request {:op :treatment/administer :subject "encounter-1"}
    :approval {:status :approved :by "dr-arai-treatment"}
    :expect :commit}

   {:id "s06" :phase 3
    :title "法域ライセンス評価 encounter-2 (spec-basis 無し)"
    :note "未登録法域の要件を advisor が創作しようとした場合の HARD hold。"
    :request {:op :jurisdiction/assess :subject "encounter-2" :no-spec? true}
    :expect :hold}

   {:id "s07" :phase 3
    :title "法域ライセンス評価 encounter-3 (人が承認)"
    :note "s08 の前提。ここで evidence を揃えることで s08 の違反を禁忌 1 件に絞る。"
    :request {:op :jurisdiction/assess :subject "encounter-3"}
    :approval {:status :approved :by "dr-kudo-jurisdiction"}
    :expect :commit}

   {:id "s08" :phase 3
    :title "治療実施 encounter-3 (患者自身の禁忌リストに一致)"
    :note "evidence は s07 で充足済み。残る違反は禁忌のみ。"
    :request {:op :treatment/administer :subject "encounter-3"}
    :expect :hold}

   {:id "s09" :phase 3
    :title "臨床医免許スクリーニング encounter-4 (免許失効)"
    :note "スクリーニング op 自身が自分の発見で HARD hold する。人には届かない。"
    :request {:op :credential/screen :subject "encounter-4"}
    :expect :hold}

   {:id "s10" :phase 3
    :title "治療実施 encounter-2 (法域評価が無い = 必要書類未充足)"
    :note "s06 が hold したため encounter-2 には assessment が存在しない。"
    :request {:op :treatment/administer :subject "encounter-2"}
    :expect :hold}

   {:id "s11" :phase 3
    :title "治療実施 encounter-1 を再度 (二重実施)"
    :note ":treated? という専用 boolean を見る。:status のライフサイクルには依存しない。"
    :request {:op :treatment/administer :subject "encounter-1"}
    :expect :hold}

   {:id "s12" :phase 1
    :title "phase 1 で法域ライセンス評価 (rollout gate が停止)"
    :note "governor は clean。停止したのは phase gate。分類の識別テスト。"
    :request {:op :jurisdiction/assess :subject "encounter-1"}
    :expect :hold}

   {:id "s13" :phase 3
    :title "臨床医免許スクリーニング encounter-3 (人が却下)"
    :note "人の却下。governor の拒否ではない。分類の識別テスト。"
    :request {:op :credential/screen :subject "encounter-3"}
    :approval {:status :rejected :by "dr-sato-review"}
    :expect :hold}])

(defn run-scenario!
  "Executes ONE scenario against the live actor and returns everything
  observed -- never anything declared. `:ledger-delta` is the slice of
  the store's append-only ledger that this scenario alone appended."
  [actor db {:keys [id phase request approval] :as scenario}]
  (let [ctx (assoc actor-context :phase phase)
        thread-id (str "thread-" id)
        before (count (store/ledger db))
        r1 (g/run* actor {:request request :context ctx} {:thread-id thread-id})
        interrupted? (= :interrupted (:status r1))
        r2 (when (and approval interrupted?)
             (g/run* actor {:approval approval} {:thread-id thread-id :resume? true}))
        final (or r2 r1)
        state (:state final)]
    (assoc scenario
           :thread-id thread-id
           :interrupted? interrupted?
           :resumed? (some? r2)
           :status (:status final)
           :proposal (:proposal state)
           :verdict (:verdict state)
           :disposition (:disposition state)
           :record (:record state)
           :run-audit (vec (:audit state))
           :ledger-delta (vec (subvec (vec (store/ledger db)) before)))))

;; ======================= hold classification =======================

(defn classify-hold
  "Classify one ledger fact that carries `:disposition :hold`.

  Dispatches on the FACT TYPE FIRST. See the ns docstring for why a
  `:violations`-based test misclassifies both of the other two kinds."
  [fact]
  (case (:t fact)
    :approval-rejected :hold/approver-rejection
    :governor-hold     (if (:phase-reason fact)
                         :hold/phase-gate
                         :hold/governor-refusal)
    :hold/unclassified))

(defn hold-facts
  "Every hold fact in the persisted ledger, tagged with its class."
  [ledger]
  (->> ledger
       (filter #(= :hold (:disposition %)))
       (mapv #(assoc % ::class (classify-hold %)))))

(defn governor-refusals
  "The HARD governor refusals only -- the build invariant counts THESE."
  [ledger]
  (filterv #(= :hold/governor-refusal (::class %)) (hold-facts ledger)))

(defn classification-anomalies
  "Facts whose class and payload disagree. Empty is the expected result;
  a non-empty result is printed on the page rather than swallowed."
  [holds]
  (vec
   (concat
    (for [f holds
          :when (and (= :hold/governor-refusal (::class f))
                     (empty? (:violations f)))]
      {:fact f :problem "governor refusal with no violations recorded"})
    (for [f holds
          :when (and (= :hold/phase-gate (::class f))
                     (seq (:violations f)))]
      {:fact f :problem "phase-gate hold carrying governor violations"})
    (for [f holds
          :when (= :hold/unclassified (::class f))]
      {:fact f :problem "hold fact of an unrecognised type"}))))

;; ==================== approver attribution (derived) ====================

(def ^:private approver-key-re
  "Key NAMES that would mean 'a human signed off on this'. Deliberately
  does NOT match `:actor` -- that is the executing actor, not an
  approver (see ns docstring)."
  #"(?i)approv|signed[-_]?by|authoriz|authoris|sign[-_]?off")

(defn approver-shaped-key? [k]
  (boolean (and (or (keyword? k) (string? k))
                (re-find approver-key-re (name k)))))

(defn- scan
  "Walks `data` and calls `(f path k v)` on every map entry."
  [data f]
  (letfn [(walk [path x]
            (cond
              (map? x) (doseq [[k v] x]
                         (f path k v)
                         (walk (conj path k) v))
              (sequential? x) (doall (map-indexed (fn [i v] (walk (conj path i) v)) x))
              :else nil))]
    (walk [] data)
    nil))

(defn attribution-hits
  "Where does `approver` actually appear in `data`?
   -> {:approver-shaped [path..] :other-key [path..]}"
  [data approver]
  (let [shaped (atom []) other (atom [])]
    (scan data
          (fn [path k v]
            (when (= v approver)
              (if (approver-shaped-key? k)
                (swap! shaped conj (conj path k))
                (swap! other conj (conj path k))))))
    {:approver-shaped @shaped :other-key @other}))

(defn persisted-surfaces
  "The store's readable surfaces, read back through the Store protocol
  AFTER the run -- this is what actually survived, not what was proposed."
  [db]
  (let [encs (store/all-encounters db)]
    [["encounters"         (vec encs)]
     ["assessments"        (into {} (for [e encs
                                          :let [a (store/assessment-of db (:id e))]
                                          :when a]
                                      [(:id e) a]))]
     ["credentials"        (into {} (for [e encs
                                          :let [c (store/credential-of db (:id e))]
                                          :when c]
                                      [(:id e) c]))]
     ["treatment-history"  (vec (store/treatment-history db))]
     ["ledger"             (vec (store/ledger db))]]))

(defn approver-attribution
  "For every approval that was actually GRANTED during the run, report
  where (if anywhere) the approver id survived into the persisted state.

  Derived at render time. There is no hard-coded claim here about which
  effects retain the approver -- if `clinic.operation/commit-record` is
  changed, this table changes with it."
  [db results]
  (let [surfaces (persisted-surfaces db)]
    (vec
     (for [r results
           granted (filter #(= :approval-granted (:t %)) (:run-audit r))
           :let [approver (:by granted)
                 per-surface (for [[nm data] surfaces
                                   :let [hits (attribution-hits data approver)]
                                   :when (or (seq (:approver-shaped hits))
                                             (seq (:other-key hits)))]
                               [nm hits])]]
       {:scenario (:id r)
        :op (:op (:request r))
        :subject (:subject (:request r))
        :effect (get-in r [:proposal :effect])
        :approver approver
        :surfaces (vec per-surface)
        :retained? (boolean (some (fn [[_ h]] (seq (:approver-shaped h))) per-surface))}))))

;; ============================== HTML ==============================

(defn esc [s]
  (-> (str s)
      (str/replace "&" "&amp;")
      (str/replace "<" "&lt;")
      (str/replace ">" "&gt;")
      (str/replace "\"" "&quot;")))

(defn stable
  "`pr-str` with sets and maps emitted in a stable (sorted) order, so two
  builds of the same run produce byte-identical output."
  [x]
  (cond
    (set? x) (str "#{" (str/join " " (map stable (sort-by pr-str x))) "}")
    (map? x) (str "{" (str/join ", " (for [[k v] (sort-by (comp pr-str key) x)]
                                       (str (stable k) " " (stable v)))) "}")
    (vector? x) (str "[" (str/join " " (map stable x)) "]")
    (sequential? x) (str "(" (str/join " " (map stable x)) ")")
    :else (pr-str x)))

(defn cell
  "A table cell value. Absence renders as an em dash -- never as the
  printed name of the empty value, which would be indistinguishable from
  a template that failed to interpolate."
  [x]
  (cond
    (nil? x) "—"
    (and (coll? x) (empty? x)) "—"
    (and (string? x) (str/blank? x)) "—"
    (string? x) (esc x)
    :else (esc (stable x))))

(defn code [x]
  (if (or (nil? x) (and (coll? x) (empty? x)))
    "—"
    (str "<code>" (cell x) "</code>")))

(defn- th-row [headers]
  (str "<tr>" (str/join (map #(str "<th>" % "</th>") headers)) "</tr>"))

(defn- td-row [cells]
  (str "<tr>" (str/join (map #(str "<td>" % "</td>") cells)) "</tr>"))

(defn table
  "A table plus its own row count, so the page never claims more rows
  than it printed."
  [headers rows]
  {:html (str "<table><thead>" (th-row headers) "</thead><tbody>"
              (str/join (map td-row rows))
              "</tbody></table>")
   :rows (count rows)})

(def ^:private class-label
  {:hold/governor-refusal   "<span class=\"err\">GOVERNOR REFUSAL (hard)</span>"
   :hold/phase-gate         "<span class=\"warn\">PHASE GATE (rollout)</span>"
   :hold/approver-rejection "<span class=\"warn\">APPROVER REJECTION (human)</span>"
   :hold/unclassified       "<span class=\"err\">UNCLASSIFIED</span>"})

(defn- outcome-label [r]
  (case (:disposition r)
    :commit   "<span class=\"ok\">COMMITTED</span>"
    :hold     "<span class=\"err\">HELD</span>"
    :escalate "<span class=\"warn\">AWAITING APPROVAL</span>"
    (cell (:disposition r))))

(defn- escalation-reason
  "Why a run stopped at :request-approval, taken from the fact the
  :decide node actually wrote."
  [r]
  (some->> (:run-audit r)
           (filter #(= :approval-requested (:t %)))
           first
           :reason))

;; ------------------------------ sections ------------------------------

(defn section [title body & [{:keys [lead]}]]
  {:title title
   :html (str "<h2>" (esc title) "</h2>"
              (when lead (str "<p class=\"subtitle\">" lead "</p>"))
              body)})

(defn scenario-section [results]
  (let [t (table ["#" "操作 (op)" "対象" "phase" "advisor 確信度"
                  "governor hard?" "違反 (rule)" "承認要求の理由" "承認者" "結末"]
                 (for [r results]
                   [(esc (:id r))
                    (code (:op (:request r)))
                    (code (:subject (:request r)))
                    (esc (str (:phase r)))
                    (cell (get-in r [:verdict :confidence]))
                    (if (get-in r [:verdict :hard?])
                      "<span class=\"err\">YES</span>"
                      "<span class=\"ok\">no</span>")
                    (code (mapv :rule (get-in r [:verdict :violations])))
                    (code (escalation-reason r))
                    (if (:resumed? r)
                      (cell (get-in r [:approval :by]))
                      "—")
                    (outcome-label r)]))]
    (assoc (section "1. シナリオ実行結果 (actor の実行そのもの)"
                    (:html t)
                    {:lead (str "各行は <code>langgraph.graph/run*</code> に対する実 1 回分。"
                                "確信度・違反・結末は実行が返した graph state から読み出している。")})
           :rows (:rows t))))

(defn hard-hold-section [refusals]
  (let [t (table ["ledger 順" "操作 (op)" "対象" "拒否した規則" "governor が拒否した内容" "advisor 確信度"]
                 (for [[i f] (map-indexed vector refusals)
                       :let [v (first (:violations f))]]
                   [(esc (str (inc i)))
                    (code (:op f))
                    (code (:subject f))
                    (str "<code class=\"err\">" (cell (:rule v)) "</code>")
                    (cell (:detail v))
                    (cell (:confidence f))]))]
    (assoc (section "2. HARD governor hold — 実際の拒否"
                    (str "<div class=\"card\"><p>これらは <strong>人が承認で上書きできない</strong> 拒否。"
                         "<code>clinic.operation</code> の <code>:decide</code> ノードが "
                         "<code>:request-approval</code> に到達させず、SSoT には一切書き込まれない。</p></div>"
                         (:html t))
                    {:lead "advisor の確信度が高くても拒否は成立している (確信度は拒否の理由ではない)。"})
           :rows (:rows t))))

(defn classification-section [holds anomalies]
  (let [t (table ["ledger fact type" ":phase-reason" ":violations" "分類" "対象"]
                 (for [f holds]
                   [(code (:t f))
                    (code (:phase-reason f))
                    (code (mapv :rule (:violations f)))
                    (get class-label (::class f) (cell (::class f)))
                    (code (:subject f))]))
        counts (frequencies (map ::class holds))]
    (assoc (section "3. hold の分類 — governor の拒否と rollout gate と人の却下を分ける"
                    (str "<div class=\"banner\">"
                         "<p>3 種類の hold のうち <strong>2 種類が同じ fact type "
                         "<code>:governor-hold</code> を共有</strong>している。"
                         "<code>:decide</code> ノードが phase-disabled な op にも "
                         "<code>governor/hold-fact</code> を再利用するため。"
                         "さらに人の却下 (<code>:approval-rejected</code>) は "
                         "<code>{:rule :approver-rejected}</code> という違反を自分で持つ。</p>"
                         "<p>したがって <code>(seq (:violations f))</code> だけで数えると "
                         "<strong>phase gate を取りこぼし、人の却下を governor の拒否として数える</strong>。"
                         "この console は fact type を先に見てから "
                         "<code>:phase-reason</code> で分割している "
                         "(<code>clinic.render-html/classify-hold</code>)。</p></div>"
                         "<ul>"
                         (str/join
                          (for [[k label] [[:hold/governor-refusal "HARD governor 拒否"]
                                           [:hold/phase-gate "rollout phase gate"]
                                           [:hold/approver-rejection "人の却下"]
                                           [:hold/unclassified "未分類"]]
                                :let [n (get counts k 0)]
                                :when (or (pos? n) (= k :hold/governor-refusal))]
                            (str "<li>" label ": <strong class=\"num\">" n "</strong> 件</li>")))
                         "</ul>"
                         (:html t)
                         (if (seq anomalies)
                           (str "<div class=\"card\"><p class=\"err\">分類の矛盾 "
                                (count anomalies) " 件:</p><ul>"
                                (str/join (for [a anomalies]
                                            (str "<li>" (esc (:problem a)) " — "
                                                 (code (:fact a)) "</li>")))
                                "</ul></div>")
                           (str "<p class=\"muted\">分類の矛盾は検出されなかった "
                                "(governor 拒否は全て違反を持ち、phase gate は 1 件も違反を持たない)。</p>")))
                    {:lead "分類器が本当に識別しているかどうかは、この表の各行が自分で示している。"})
           :rows (:rows t))))

(defn register-section [db]
  (let [encs (store/all-encounters db)
        enc-t (table ["encounter" "患者" "主訴" "提案治療" "禁忌" "免許 current?" "治療実施済み?" "法域" "実施番号"]
                     (for [e encs]
                       [(code (:id e))
                        (cell (:patient e))
                        (cell (:chief-complaint e))
                        (code (:proposed-treatment e))
                        (code (:contraindications e))
                        (if (:clinician-license-current? e)
                          "<span class=\"ok\">true</span>"
                          "<span class=\"err\">false</span>")
                        (if (:treated? e)
                          "<span class=\"ok\">true</span>"
                          "<span class=\"muted\">false</span>")
                        (cell (:jurisdiction e))
                        (code (:administration-number e))]))
        as-t (table ["encounter" "法域" "spec-basis" "legal-basis" "必要書類 (checklist)" "その他のキー"]
                    (for [e encs
                          :let [a (store/assessment-of db (:id e))]
                          :when a]
                      [(code (:id e))
                       (cell (:jurisdiction a))
                       (cell (:spec-basis a))
                       (cell (:legal-basis a))
                       (str "<ol>" (str/join (for [c (:checklist a)]
                                               (str "<li>" (cell c) "</li>"))) "</ol>")
                       (code (vec (sort (map pr-str (remove #{:jurisdiction :spec-basis :legal-basis :checklist}
                                                            (keys a))))))]))
        cr-t (table ["encounter" "免許判定" "payload 全体"]
                    (for [e encs
                          :let [c (store/credential-of db (:id e))]
                          :when c]
                      [(code (:id e))
                       (if (= :current (:verdict c))
                         "<span class=\"ok\">:current</span>"
                         (str "<span class=\"err\">" (cell (:verdict c)) "</span>"))
                       (code c)]))
        tx-t (table ["record_id" "kind" "encounter_id" "法域" "immutable"]
                    (for [r (store/treatment-history db)]
                      [(code (get r "record_id"))
                       (cell (get r "kind"))
                       (code (get r "encounter_id"))
                       (cell (get r "jurisdiction"))
                       (cell (get r "immutable"))]))]
    (assoc (section "4. 実行後の register (SSoT を読み戻したもの)"
                    (str "<h3>encounters (" (:rows enc-t) ")</h3>" (:html enc-t)
                         "<h3>jurisdiction assessments (" (:rows as-t) ")</h3>"
                         (if (pos? (:rows as-t)) (:html as-t)
                             "<p class=\"muted\">コミットされた法域評価は無い。</p>")
                         "<h3>credential screenings (" (:rows cr-t) ")</h3>"
                         (if (pos? (:rows cr-t)) (:html cr-t)
                             "<p class=\"muted\">コミットされた免許スクリーニングは無い。</p>")
                         "<h3>treatment-administration drafts (" (:rows tx-t) ")</h3>"
                         (if (pos? (:rows tx-t)) (:html tx-t)
                             "<p class=\"muted\">実施記録ドラフトは 1 件も作られていない。</p>"))
                    {:lead (str "hold されたシナリオが 1 つも SSoT を変更していないことは、"
                                "この表と section 1 を突き合わせれば読める。")})
           :rows (+ (:rows enc-t) (:rows as-t) (:rows cr-t) (:rows tx-t)))))

(defn attribution-section [db results attribution]
  (let [granted (count attribution)
        retained (count (filter :retained? attribution))
        ledger-approval-facts (count (filter #(= :approval-granted (:t %)) (store/ledger db)))
        run-approval-facts (count (for [r results
                                        f (:run-audit r)
                                        :when (= :approval-granted (:t f))] f))
        t (table ["#" "操作 (op)" "対象" "commit effect" "承認者" "永続化された場所" "承認者が残ったか"]
                 (for [a attribution]
                   [(esc (:scenario a))
                    (code (:op a))
                    (code (:subject a))
                    (code (:effect a))
                    (code (:approver a))
                    (if (seq (:surfaces a))
                      (str "<ul>"
                           (str/join
                            (for [[nm hits] (:surfaces a)]
                              (str "<li><code>" (esc nm) "</code>: "
                                   (if (seq (:approver-shaped hits))
                                     (str "<span class=\"ok\">approver 形状のキー</span> "
                                          (code (mapv #(str/join "/" (map (fn [p] (if (keyword? p) (name p) (str p))) %))
                                                      (:approver-shaped hits))))
                                     (str "<span class=\"warn\">approver 形状でないキー</span> "
                                          (code (mapv #(str/join "/" (map (fn [p] (if (keyword? p) (name p) (str p))) %))
                                                      (:other-key hits)))))
                                   "</li>")))
                           "</ul>")
                      "<span class=\"err\">どの register にも ledger にも現れない</span>")
                    (if (:retained? a)
                      "<span class=\"ok\">残った</span>"
                      "<span class=\"err\">失われた</span>")]))]
    (assoc (section "5. 承認者の帰属 — 実行後に走査して求めた結果"
                    (str "<div class=\"banner\"><p>この節はレンダリング時に "
                         "<code>persisted-surfaces</code> を走査して求めている。"
                         "「この repo にはこの欠陥がある」と書き込んだ静的な文章ではないので、"
                         "<code>clinic.operation/commit-record</code> が直れば表も直る。</p>"
                         "<p>走査は <code>:actor</code> を承認者として読まない — "
                         "<code>:actor</code> は<strong>実行した</strong> actor "
                         "(<code>" (esc (:actor-id actor-context)) "</code>) であって承認者ではない。"
                         "このビルドは actor と全承認者に別々の id を与えているので、"
                         "取り違えは表に出る。</p></div>"
                         (:html t)
                         "<ul>"
                         "<li>実行中に発生した <code>:approval-granted</code> fact: <strong class=\"num\">"
                         run-approval-facts "</strong> 件</li>"
                         "<li>そのうち永続 ledger に残っているもの: <strong class=\"num"
                         (when (zero? ledger-approval-facts) " err") "\">"
                         ledger-approval-facts "</strong> 件</li>"
                         "<li>承認者 id が永続状態に残った commit: <strong class=\"num\">"
                         retained "</strong> / " granted "</li>"
                         "</ul>"
                         (when (< ledger-approval-facts run-approval-facts)
                           (str "<div class=\"card\"><p class=\"err\">開示 (このタスクでは修正していない):</p>"
                                "<p><code>clinic.operation</code> の <code>:commit</code> ノードは "
                                "<code>commit-fact</code> だけを ledger に追記し、"
                                "<code>:request-approval</code> が作った "
                                "<code>:approval-granted</code> fact を追記しない。"
                                "「誰が承認したか」は監査 ledger 単体からは復元できず、"
                                "effect が <code>:payload</code> を書く register に残っている場合だけ読める。</p>"
                                "<p>rendering タスクの中で governor / operation を書き換えるのは"
                                "越権なので、ここでは開示に留める。</p></div>")))
                    {:lead "承認者が永続状態のどこに残ったか (残らなかったか) を実測している。"})
           :rows (:rows t))))

(defn ledger-section [db]
  (let [led (store/ledger db)
        t (table ["#" "fact type" "操作 (op)" "対象" "disposition" "実行 actor" "basis / violations"]
                 (for [[i f] (map-indexed vector led)]
                   [(esc (str (inc i)))
                    (let [cls (when (= :hold (:disposition f)) (classify-hold f))]
                      (str "<code>" (cell (:t f)) "</code>"
                           (when cls (str "<br>" (get class-label cls "")))))
                    (code (:op f))
                    (code (:subject f))
                    (if (= :commit (:disposition f))
                      "<span class=\"ok\">commit</span>"
                      "<span class=\"err\">hold</span>")
                    (code (:actor f))
                    (if (seq (:violations f))
                      (code (mapv :rule (:violations f)))
                      (code (:basis f)))]))]
    (assoc (section "6. 監査 ledger (append-only、実行が書いたそのまま)"
                    (str (:html t)
                         "<p class=\"muted\">"
                         "<code>実行 actor</code> 列は <code>:actor</code>、つまり操作を実行した actor。"
                         "承認者ではない (section 5 参照)。</p>")
                    {:lead (str "commit " (count (filter #(= :commit (:disposition %)) led))
                                " 件 / hold " (count (filter #(= :hold (:disposition %)) led)) " 件。")})
           :rows (:rows t))))

(defn coverage-section []
  (let [cov (facts/coverage)
        t (table ["iso3" "所管当局" "法的根拠" "出典 (provenance)" "必要書類 数"]
                 (for [iso3 (sort (keys facts/catalog))
                       :let [m (facts/spec-basis iso3)]]
                   [(code iso3)
                    (cell (:owner-authority m))
                    (cell (:legal-basis m))
                    (str "<code>" (cell (:provenance m)) "</code>")
                    (esc (str (count (:required-evidence m))))]))]
    (assoc (section "7. 法域 spec-basis のカバレッジ (正直な申告)"
                    (str (:html t)
                         "<div class=\"banner\"><p>" (esc (:note cov)) "</p>"
                         "<p>登録済み: <strong class=\"num\">" (:covered cov) "</strong> / "
                         (:requested cov) " 法域。"
                         "未登録の法域には spec-basis が<strong>無い</strong> — "
                         "advisor が要件を創作しようとすれば section 2 の 1 行目のように拒否される。</p></div>")
                    {:lead "カバレッジを大きく見せるために法域の要件を創作しない、という約束の実測値。"})
           :rows (:rows t))))

;; ============================ assembly ============================

(defn vendored-style
  "The jp-go-dds (デジタル庁デザインシステム) CSS this repo already
  vendors into `docs/index.html`. Reused verbatim so the console and the
  product LP cannot drift apart -- there is exactly ONE vendored copy in
  the repo. Throws if it is missing: a console that silently renders
  unstyled is a build that measured nothing and reported success."
  [root]
  (let [f (io/file root "docs/index.html")]
    (when-not (.exists f)
      (throw (ex-info "docs/index.html not found -- cannot source the vendored jp-go-dds CSS"
                      {:path (.getPath f)})))
    (let [html (slurp f)
          start (str/index-of html "<style>")
          end (str/index-of html "</style>")]
      (when-not (and start end (< start end))
        (throw (ex-info "no <style> block in docs/index.html" {:path (.getPath f)})))
      (subs html (+ start (count "<style>")) end))))

(defn structural-problems
  "Cheap, mechanical checks on the generated body. Returns a vector of
  problem strings; `-main` refuses to write a file when it is non-empty."
  [body]
  (let [balance (fn [tag]
                  (let [o (count (re-seq (re-pattern (str "<" tag "[ >]")) body))
                        c (count (re-seq (re-pattern (str "</" tag ">")) body))]
                    (when (not= o c)
                      (str "unbalanced <" tag ">: " o " open / " c " close"))))]
    (vec
     (remove nil?
             (concat
              (map balance ["div" "table" "thead" "tbody" "tr" "td" "th" "ul" "ol" "li" "p" "h2" "h3" "code" "span" "strong"])
              [(when (re-find #"%[sd]" body) "unresolved format token (%s / %d) in body")
               (when (re-find #"nil\s*<" body) "literal empty-value token leaked into a cell")
               (when (re-find #"\{\{" body) "unresolved template braces in body")])))))

(defn build-page
  "Runs the actor and returns {:html .. :sections .. :refusals .. ..}.
  Pure of any wall-clock or random input: no timestamps, no UUIDs, no
  run ids -- two builds of the same tree produce identical bytes."
  [root]
  (let [db (store/seed-db)
        actor (op/build db)
        results (mapv #(run-scenario! actor db %) scenarios)
        led (vec (store/ledger db))
        holds (hold-facts led)
        refusals (filterv #(= :hold/governor-refusal (::class %)) holds)
        anomalies (classification-anomalies holds)
        attribution (approver-attribution db results)
        blueprint (edn/read-string (slurp (io/file root "blueprint.edn")))
        secs [(scenario-section results)
              (hard-hold-section refusals)
              (classification-section holds anomalies)
              (register-section db)
              (attribution-section db results attribution)
              (ledger-section db)
              (coverage-section)]
        summary (table ["指標" "値"]
                       [["シナリオ実行数" (str "<strong class=\"num\">" (count results) "</strong>")]
                        ["ledger fact 総数" (str "<strong class=\"num\">" (count led) "</strong>")]
                        ["commit された操作" (str "<strong class=\"num\">"
                                              (count (filter #(= :commit (:disposition %)) led))
                                              "</strong>")]
                        ["HARD governor 拒否"
                         (str "<strong class=\"num err\">" (count refusals) "</strong>")]
                        ["rollout phase gate による停止"
                         (str "<strong class=\"num\">"
                              (count (filter #(= :hold/phase-gate (::class %)) holds))
                              "</strong>")]
                        ["人の承認者による却下"
                         (str "<strong class=\"num\">"
                              (count (filter #(= :hold/approver-rejection (::class %)) holds))
                              "</strong>")]
                        ["人の承認で commit された操作"
                         (str "<strong class=\"num\">"
                              (count (filter :resumed? results)) "</strong>")]
                        ["治療実施ドラフト記録"
                         (str "<strong class=\"num\">"
                              (count (store/treatment-history db)) "</strong>")]])
        expectation-mismatches
        (vec (for [r results
                   :when (and (:expect r) (not= (:expect r) (:disposition r)))]
               (str (:id r) ": expected " (:expect r) ", observed " (:disposition r))))
        body (str
              "<div class=\"container\">"
              "<div class=\"bar\"><span class=\"badge\">ISIC "
              (esc (:itonami.blueprint/isic-rev5 blueprint))
              "</span><span class=\"badge\">"
              (esc (name (:itonami.blueprint/governor blueprint)))
              "</span><span class=\"badge\">"
              (esc (:itonami.blueprint/license blueprint))
              "</span></div>"
              "<h1>" (esc (:itonami.blueprint/name blueprint)) " — operator console</h1>"
              "<p class=\"subtitle\"><code>"
              (esc (:itonami.blueprint/id blueprint))
              "</code> — このページは <code>clinic.render-html</code> がビルド時に actor を"
              "実際に走らせて生成している。行・数値・判定はすべて実行結果を読み戻したもので、"
              "手書きの値は 1 つも無い。</p>"
              "<div class=\"card\"><p><strong>読み方:</strong> "
              "advisor (<code>clinic.clinicopsllm</code>) は提案しかしない。"
              "提案は独立した <code>clinic.governor</code> と rollout phase gate "
              "(<code>clinic.phase</code>) を必ず通る。"
              "<code>:treatment/administer</code> はどの phase でも自動コミットしない。</p></div>"
              (:html summary)
              (when (seq expectation-mismatches)
                (str "<div class=\"card\"><p class=\"err\">シナリオの意図と実測の不一致 "
                     (count expectation-mismatches) " 件:</p><ul>"
                     (str/join (for [m expectation-mismatches] (str "<li>" (esc m) "</li>")))
                     "</ul></div>"))
              (str/join (map :html secs))
              "<div class=\"footer\"><p>生成: <code>clojure -M:dev:render-html</code> "
              "(<code>src/clinic/render_html.clj</code>)。"
              "入力は <code>clinic.store/seed-db</code> の seed encounter "
              (count (store/all-encounters db)) " 件のみ。"
              "外部ネットワーク呼び出し・タイムスタンプ・UUID は使っていないので、"
              "同じ tree からは常に同じバイト列が出る。</p>"
              "<p>スタイルは <code>docs/index.html</code> が vendoring している "
              "jp-go-dds (デジタル庁デザインシステム) をそのまま再利用している。</p></div>"
              "</div>")
        problems (structural-problems body)]
    {:body body
     :problems problems
     :sections (mapv :title secs)
     :section-rows (mapv (juxt :title :rows) secs)
     :results results
     :ledger led
     :holds holds
     :refusals refusals
     :anomalies anomalies
     :attribution attribution
     :expectation-mismatches expectation-mismatches
     :html (str "<!DOCTYPE html>\n<html lang=\"ja\">\n<head>\n"
                "<meta charset=\"UTF-8\">\n"
                "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\">\n"
                "<title>" (esc (:itonami.blueprint/name blueprint))
                " — operator console — " (esc (:itonami.blueprint/id blueprint)) "</title>\n"
                "<style>" (vendored-style root) "</style>\n"
                "</head>\n<body>\n" body "\n</body>\n</html>\n")}))

(defn -main
  "Renders the console. THROWS (and writes nothing) when the run produced
  no HARD governor hold, or when the generated body fails the structural
  checks. The refusal count is a build invariant, not a convention: a
  page that showed only successful commits would be evidence that the
  censor is untested, and publishing it would be worse than a red build."
  [& args]
  (let [out (or (first args) default-out)
        root (System/getProperty "user.dir")
        {:keys [html body problems sections section-rows refusals holds
                anomalies expectation-mismatches]} (build-page root)]
    (when (empty? refusals)
      (throw (ex-info (str "REFUSING TO WRITE " out
                           " -- the run produced 0 HARD governor holds. Either the seed "
                           "no longer exercises clinic.governor, or the governor stopped "
                           "refusing. Publishing an all-green console here would assert a "
                           "censor that was never observed to censor.")
                      {:ledger-holds (count holds) :out out})))
    (when (seq problems)
      (throw (ex-info (str "REFUSING TO WRITE " out " -- structural problems: "
                           (str/join "; " problems))
                      {:problems problems})))
    (io/make-parents (io/file out))
    (spit (io/file out) html)
    (println "wrote" out (str "(" (count (.getBytes ^String html "UTF-8")) " bytes)"))
    (println "sections:" (count sections))
    (doseq [[t n] section-rows] (println "  -" t "->" n "rows"))
    (println "HARD governor refusals:" (count refusals))
    (doseq [f refusals]
      (println "  -" (:op f) (:subject f) "->" (mapv :rule (:violations f))))
    (println "hold classes:" (frequencies (map ::class holds)))
    (when (seq anomalies)
      (println "CLASSIFICATION ANOMALIES:" anomalies))
    (when (seq expectation-mismatches)
      (println "EXPECTATION MISMATCHES:" expectation-mismatches))
    (println "body bytes (excl. vendored CSS):" (count (.getBytes ^String body "UTF-8")))))
