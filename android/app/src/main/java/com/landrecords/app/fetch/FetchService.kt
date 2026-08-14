package com.landrecords.app.fetch

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import com.landrecords.app.MainActivity
import com.landrecords.app.R
import com.landrecords.app.data.model.RecordType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Background auto-fetch — docs/specs/2026-08-14-unified-db-and-autofetch-design.md §6.
 *
 * A **foreground service** hosting offscreen WebViews. Foreground is required: Android kills a
 * background process mid-fetch otherwise, and a half-written record set is exactly what the
 * blob store and the queue exist to avoid.
 *
 * ### Why the WebViews are offscreen and it still works
 *
 * MEASURED on SM-X730 (API 36), 2026-08-14: an offscreen WebView in a foreground service
 * renders **identically** whether the app is foreground, backgrounded, or the screen is off —
 * byte-identical print output and pixel-identical rasterisation in all four states. So the
 * fetch survives the user switching apps or pocketing the phone, and there is no need to defer
 * PDF rendering. The WebView is created against the *service* context and laid out manually,
 * so the existing injections work unchanged.
 *
 * Two things that measurement did NOT prove, and which this class is built defensively around:
 *  - **Doze.** A partial wake lock is held, as the probe did. Whether deep Doze eventually
 *    suspends the service is a separate question — the queue's resume-after-death design
 *    covers it either way, which is why [FetchQueue.reclaimStale] runs on every start.
 *  - **AnyRoR's own JS under background timer throttling.** The probe used a static local page.
 *    WebForms postbacks are event-driven rather than timer-driven, so this should be fine, but
 *    it needs watching on the first real background run — hence the explicit per-step logging.
 *
 * ### One fetch at a time, no parallelism
 *
 * The whole queue drains through a SINGLE sequential lane (see [drainAll]) — no AnyRoR/iRCMS
 * concurrency, no cascade running while a download runs elsewhere. This is partly a correctness
 * floor and partly a deliberate simplicity choice:
 *
 *  - `CookieManager` is process-global, so every WebView on `anyror.gujarat.gov.in` shares one
 *    `ASP.NET_SessionId` and one `__VIEWSTATE`. Two concurrent AnyRoR cascades would corrupt
 *    each other and silently fetch the wrong survey — a data error, not a slow one. Serial
 *    fetching makes that impossible by construction.
 *  - Running AnyRoR and iRCMS at once was faster but meant "what is it doing now?" had two
 *    answers, and let a giant VF-7/12 job block the INTEGRATED records that matter most. One
 *    lane, in priority order, is easier to follow and puts the important records first.
 */
class FetchService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var wakeLock: PowerManager.WakeLock? = null
    private var worker: Job? = null

    /** One pacer per host, shared by every fetcher for that host. */
    private val anyrorPacer = FetchPacer()
    private val ircmsPacer = FetchPacer()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        startForeground(NOTIFICATION_ID, buildNotification("Starting…"))
        acquireWakeLock()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (worker?.isActive != true) worker = scope.launch { drain() }
        // START_STICKY: if Android kills us anyway, come back and resume from the queue.
        return START_STICKY
    }

    /**
     * Drain the queue, ONE fetch at a time, in a fixed priority order:
     *
     *   all INTEGRATED (the primary 7/12 records — the ones that matter most) →
     *   all IRCMS cases → all VF-7/12 scan histories (slowest; a single survey can be 80 scans).
     *
     * Deliberately NOT parallel. An earlier version ran AnyRoR and iRCMS concurrently, but that
     * made "what is it doing right now?" have two answers at once, and let one giant VF-7/12 job
     * hog its lane while the important INTEGRATED records waited. A single sequential lane is
     * simpler to reason about and puts the records dad cares about first. The per-host pacers are
     * kept (a WAF block on AnyRoR shouldn't penalise the next iRCMS item), selected per record
     * type; the old cross-lane `anyrorLock` is gone — one lane is inherently serialized.
     */
    private suspend fun drain() {
        markRun(this, started = true)
        FetchQueue.reclaimStale(this)
        drainAll()
        markRun(this, started = false)
        updateNotification("Done")
        stopSelf()
    }

    /** The single global order. claimNext already tie-breaks INTEGRATED→IRCMS→VF712 within a tick. */
    private val allTypes: Collection<String> get() = ANYROR_TYPES + IRCMS_TYPES

    private fun pacerFor(recordType: String): FetchPacer =
        if (recordType in IRCMS_TYPES) ircmsPacer else anyrorPacer

    private suspend fun drainAll() {
        while (true) {
            val item = FetchQueue.claimNext(this, allTypes)
            if (item == null) {
                // Nothing claimable now: stop if the queue is empty, else wait out the cooldown
                // (stopping here would strand pending-but-not-due items until the app reopened).
                val due = FetchQueue.nextDueAt(this, allTypes) ?: return
                val wait = (due - System.currentTimeMillis()).coerceIn(1_000L, 120_000L)
                android.util.Log.i("LR", "FetchService: idle, next item due in ${wait / 1000}s")
                delay(wait)
                continue
            }

            val pacer = pacerFor(item.recordType)
            if (pacer.isParked()) {
                // This host has refused us the maximum number of times. Skip its remaining items
                // for now (mark them to retry later) rather than hammering — the OTHER host's
                // items still drain, because the pacer is per-host.
                FetchQueue.markFailed(this, item, "host backing off — will retry", retryAfterMs = 5 * 60_000)
                continue
            }

            val remaining = FetchQueue.pendingCount(this)
            updateNotification("${item.recordType} · $remaining left")

            pacer.awaitTurn()
            val result = runCatching { runOne(item) }

            val outcome = result.getOrNull()
            when {
                // A HOST BLOCK — a real 429/403/5xx or WAF response — is the ONLY thing that
                // escalates the shared pacer and can park the lane. This is the whole point of
                // the backoff: stop hammering a site that is refusing us.
                outcome == Outcome.BLOCKED -> {
                    pacer.onBlocked()
                    FetchQueue.markFailed(this, item, "blocked — backing off", pacer.penaltyMs())
                }
                // A per-ITEM failure (bad captcha, unresolvable cascade, render error) is NOT a
                // host block. It must NOT touch the pacer: doing so parked the entire AnyRoR lane
                // after 6 unrelated survey failures and stopped INTEGRATED cold, while iRCMS —
                // on its own pacer — kept working. The item is marked failed and we move to the
                // next survey; the host is presumed healthy until it actually says otherwise.
                result.isFailure -> {
                    val msg = result.exceptionOrNull()?.message ?: "error"
                    android.util.Log.w("LR", "FetchService: item failed (${item.recordType} ${item.surveyUid}): $msg")
                    FetchQueue.markFailed(this, item, msg, retryAfterMs = 60_000)
                }
                outcome == Outcome.OK -> {
                    pacer.onSuccess()
                    FetchQueue.markDone(this, item.uid)
                }
                else -> {
                    // A clean "no records for this survey" is a real answer, not a failure.
                    pacer.onSuccess()
                    FetchQueue.markDone(this, item.uid)
                }
            }
            delay(50) // yield, so a long queue cannot starve the main thread
        }
    }

    enum class Outcome { OK, EMPTY, BLOCKED }

    private val filer: FetchFiler by lazy {
        val app = applicationContext as com.landrecords.app.LandRecordsApp
        FetchFiler(applicationContext, app.repository, app.libraryWriter)
    }

    /**
     * File one AnyRoR result and return its [Outcome], THROWING on a per-item Failed exactly as the
     * single-type path does (so drainAll's runCatching handles it). Does not touch the queue.
     */
    private suspend fun applyAnyRor(
        item: FetchQueue.Item,
        type: RecordType,
        survey: com.landrecords.app.data.db.SurveyEntity,
        property: com.landrecords.app.data.db.PropertyEntity,
        r: AnyRorDriver.Result,
    ): Outcome = when (r) {
        is AnyRorDriver.Result.Ok ->
            if (filer.fileAnyRor(item.surveyUid, type, survey, property, r.capture)) Outcome.OK
            else throw IllegalStateException("could not file the ${type.name} record")
        is AnyRorDriver.Result.Vf712Ok ->
            if (filer.fileVf712(item.surveyUid, survey, property, r.scans, r.rawHtml)) Outcome.OK
            else throw IllegalStateException("could not file the VF-7/12 scans")
        is AnyRorDriver.Result.Empty -> { filer.markEmpty(item.surveyUid, survey, type); Outcome.EMPTY }
        is AnyRorDriver.Result.Blocked -> { android.util.Log.w("LR", "FetchService: blocked — ${r.detail}"); Outcome.BLOCKED }
        is AnyRorDriver.Result.Failed -> throw IllegalStateException(r.detail)
    }

    /**
     * File + mark the VF-7/12 SIBLING processed inside an INTEGRATED session — drainAll marks only
     * the primary item, so its sibling is marked here. Mirrors drainAll's per-item rule: a per-item
     * failure never touches the pacer, and a session block is escalated by the primary item (not
     * double-counted here).
     */
    private suspend fun markSibling(
        sibling: FetchQueue.Item,
        r: AnyRorDriver.Result,
        survey: com.landrecords.app.data.db.SurveyEntity,
        property: com.landrecords.app.data.db.PropertyEntity,
    ) {
        val outcome = runCatching { applyAnyRor(sibling, RecordType.VF712, survey, property, r) }
        when {
            outcome.getOrNull() == Outcome.BLOCKED ->
                FetchQueue.markFailed(this, sibling, "blocked — backing off", anyrorPacer.penaltyMs())
            outcome.isFailure ->
                FetchQueue.markFailed(this, sibling, outcome.exceptionOrNull()?.message ?: "error", 60_000)
            else -> { anyrorPacer.onSuccess(); FetchQueue.markDone(this, sibling.uid) }
        }
    }

    /**
     * Fetch one queue item, headlessly.
     *
     * DEEDS and ENTRIES are never fetched on their own: one AnyRoR type-8 page yields the
     * integrated record, the VF-6 entry grid AND the Sub-registrar deed rows, so fetching them
     * separately would burn three captchas for one page's worth of data. They are marked done
     * by the INTEGRATED pass, and skipped here.
     */
    private suspend fun runOne(item: FetchQueue.Item): Outcome {
        val resolved = filer.resolve(item.surveyUid)
        if (resolved == null) {
            // The survey was deleted after being queued. Not an error — drop the item.
            android.util.Log.i("LR", "FetchService: ${item.surveyUid} no longer exists — dropping")
            return Outcome.EMPTY
        }
        val (survey, property) = resolved
        // DB review B1: guarantee the place + survey parent sync rows exist before any child.
        filer.ensureParents(survey, property)

        return when (item.recordType) {
            "DEEDS", "ENTRIES" -> {
                // Filed by the INTEGRATED pass on the same page (see the memory note).
                Outcome.EMPTY
            }

            "INTEGRATED", "VF712" -> {
                val type = if (item.recordType == "VF712") RecordType.VF712 else RecordType.INTEGRATED
                val driver = AnyRorDriver(applicationContext, anyrorPacer)
                // The cascade matches on the GUJARATI names — that is what the site lists.
                val districtGu = property.districtGu.ifBlank { property.district }
                val talukaGu = property.talukaGu.ifBlank { property.taluka }
                val villageGu = property.villageGu.ifBlank { property.village }

                // INTEGRATED pulls its VF-7/12 sibling into ONE session (one WebView + one captcha
                // model + one warm session + a shared geo cascade). INTEGRATED is always drained
                // first, so a lone VF-7/12 item means its sibling is already done — fetch it alone.
                val sibling = if (type == RecordType.INTEGRATED)
                    FetchQueue.claimSibling(this, item.surveyUid, "VF712") else null

                if (sibling != null) {
                    val both = driver.fetchBoth(districtGu, talukaGu, villageGu, survey.surveyNo, item.surveyUid)
                    // drainAll marks only the primary row, so file + mark the sibling here…
                    markSibling(sibling, both.vf712, survey, property)
                    // …and hand the INTEGRATED half back as this item's outcome.
                    applyAnyRor(item, RecordType.INTEGRATED, survey, property, both.integrated)
                } else {
                    val r = driver.fetch(
                        recordType = type,
                        districtGu = districtGu, talukaGu = talukaGu, villageGu = villageGu,
                        surveyNo = survey.surveyNo, surveyUid = item.surveyUid,
                    )
                    applyAnyRor(item, type, survey, property, r)
                }
            }

            "IRCMS" -> {
                when (val r = IrcmsDriver(applicationContext, ircmsPacer).fetch(
                    district = property.district, taluka = property.taluka,
                    village = property.village, surveyNo = survey.surveyNo,
                )) {
                    is IrcmsDriver.Result.Ok -> {
                        if (filer.fileIrcms(item.surveyUid, survey, property, r.captures)) Outcome.OK
                        else throw IllegalStateException("could not file the iRCMS cases")
                    }
                    is IrcmsDriver.Result.Empty -> {
                        filer.markEmpty(item.surveyUid, survey, RecordType.IRCMS)
                        Outcome.EMPTY
                    }
                    is IrcmsDriver.Result.Blocked -> {
                        android.util.Log.w("LR", "FetchService: iRCMS blocked — ${r.detail}")
                        Outcome.BLOCKED
                    }
                    is IrcmsDriver.Result.Failed -> throw IllegalStateException(r.detail)
                }
            }

            else -> {
                android.util.Log.w("LR", "FetchService: unknown record type '${item.recordType}'")
                Outcome.EMPTY
            }
        }
    }

    // ---- lifecycle plumbing ------------------------------------------------

    private fun acquireWakeLock() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "LandRecords:fetch").apply {
            setReferenceCounted(false)
            acquire(30 * 60_000L) // a bounded lock; the drain re-acquires if it outlives this
        }
    }

    override fun onDestroy() {
        runCatching { wakeLock?.release() }
        scope.cancel()
        super.onDestroy()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Fetching records", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Shows progress while land records are downloaded in the background."
                setShowBadge(false)
            },
        )
    }

    private fun buildNotification(text: String): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Fetching land records")
            .setContentText(text)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(open)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(text: String) {
        runCatching {
            getSystemService(NotificationManager::class.java)
                .notify(NOTIFICATION_ID, buildNotification(text))
        }
    }

    companion object {
        private const val CHANNEL_ID = "fetch"
        private const val NOTIFICATION_ID = 4201
        const val ACTION_STOP = "com.landrecords.app.fetch.STOP"

        /** Record types served by AnyRoR — one page yields integrated + entries + deeds. */
        val ANYROR_TYPES = setOf("INTEGRATED", "ENTRIES", "DEEDS", "VF712")

        /** Record types served by iRCMS. */
        val IRCMS_TYPES = setOf("IRCMS")

        /**
         * Every type auto-fetched when a property is added (§6).
         *
         * DEEDS and ENTRIES are deliberately absent: one AnyRoR type-8 page yields the
         * integrated record, the VF-6 entry grid AND the Sub-registrar deed rows, so queueing
         * them separately would burn three captchas for one page's worth of data. The
         * INTEGRATED pass records all three. (They remain in [ANYROR_TYPES] so that any rows
         * queued by an older build are still drained rather than stranded.)
         */
        val ALL_TYPES = listOf("INTEGRATED", "VF712", "IRCMS")

        /** Queue a survey's whole record set and make sure the service is running. */
        suspend fun enqueueSurvey(context: Context, surveyUid: String) {
            FetchQueue.enqueue(context, surveyUid, ALL_TYPES)
            start(context)
        }

        private const val RUN_PREFS = "fetch_run"

        /**
         * Record when the current drain started / ended, so the Fetch-status screen can show
         * "running for 4m 12s" (live) or the last run's duration. In prefs, not the DB, because
         * it is a UI convenience about THIS device's activity, not synced land data.
         */
        fun markRun(context: Context, started: Boolean) {
            val p = context.getSharedPreferences(RUN_PREFS, Context.MODE_PRIVATE)
            if (started) {
                // Only stamp a new start if a run isn't already in progress (a resume mid-run
                // keeps the original start, so elapsed reflects the whole batch).
                if (p.getLong("ended_at", 0) >= p.getLong("started_at", 0)) {
                    p.edit().putLong("started_at", System.currentTimeMillis()).putLong("ended_at", 0).apply()
                }
            } else {
                p.edit().putLong("ended_at", System.currentTimeMillis()).apply()
            }
        }

        /** (startedAt, endedAt) — endedAt is 0 while a run is in progress. */
        fun runTimes(context: Context): Pair<Long, Long> {
            val p = context.getSharedPreferences(RUN_PREFS, Context.MODE_PRIVATE)
            return p.getLong("started_at", 0) to p.getLong("ended_at", 0)
        }

        fun start(context: Context) {
            val i = Intent(context, FetchService::class.java)
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(i)
                else context.startService(i)
                android.util.Log.i("LR", "FetchService.start dispatched")
            }.onFailure { android.util.Log.w("LR", "FetchService start refused: ${it.message}") }
        }

        fun stop(context: Context) {
            runCatching {
                context.startService(Intent(context, FetchService::class.java).setAction(ACTION_STOP))
            }
        }
    }
}
