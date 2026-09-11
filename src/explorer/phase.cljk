(ns explorer.phase
  "Phase 0→3 staged rollout — where the ExplorerGovernor answers 'is this
  allowed?', the phase answers 'how much autonomy does this actor have
  *yet*?'. It can only make the actor MORE conservative: it downgrades a
  governor-clean commit to approval or hold, never the reverse.

    Phase 0  read-only     — `:disclosure/query` only (still governed)
    Phase 1  assisted-index— `:block/index` / `:tx/index` allowed, each
                             needs human approval
    Phase 2  + labels      — adds `:label/assert` and `:reorg/resolve`
                             (still approval-only)
    Phase 3  supervised    — governor-clean, corroborated, high-confidence
                             indexing may auto-commit

  Two ops are deliberately absent from EVERY phase's `:auto` set:

    :label/assert   — attaching a name to someone else's address is never
                      something this actor does unattended, however well
                      sourced. (The governor separately always-escalates
                      the allegation subset; this is the broader rule.)
    :reorg/resolve  — a reorg means already-published facts may now be
                      false. What to do about that is a human decision.

  `default-phase` is the most conservative phase, not the most permissive:
  a caller who simply omits `:phase` must not silently receive maximum
  autonomy (the accidental-fail-open shape found and fixed across the
  sibling actors — see `marketdata.phase/default-phase`)."
  )

(def read-ops  #{:disclosure/query})
(def write-ops #{:block/index :tx/index :label/assert :reorg/resolve})

(def phases
  {0 {:label "read-only"       :writes #{}
                               :auto #{}}
   1 {:label "assisted-index"  :writes #{:block/index :tx/index}
                               :auto #{}}
   2 {:label "assisted-labels" :writes #{:block/index :tx/index :label/assert :reorg/resolve}
                               :auto #{}}
   3 {:label "supervised-auto" :writes #{:block/index :tx/index :label/assert :reorg/resolve}
                               :auto #{:block/index :tx/index}}})

(def default-phase 1)

(defn gate
  "Adjust a governor disposition for the rollout phase.
  -> {:disposition kw :reason kw|nil}"
  [phase {:keys [op]} governor-disposition]
  (let [{:keys [writes auto]} (get phases phase (get phases default-phase))]
    (cond
      (= :hold governor-disposition)      {:disposition :hold :reason nil}
      (contains? read-ops op)             {:disposition governor-disposition :reason nil}
      (not (contains? writes op))         {:disposition :hold :reason :phase-disabled}
      (and (= :commit governor-disposition)
           (not (contains? auto op)))     {:disposition :escalate :reason :phase-approval}
      :else                               {:disposition governor-disposition :reason nil})))

(defn verdict->disposition
  [verdict]
  (cond (:hard? verdict) :hold
        (:escalate? verdict) :escalate
        :else :commit))
