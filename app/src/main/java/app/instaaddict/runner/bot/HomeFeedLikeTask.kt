package app.instaaddict.runner.bot

import android.os.SystemClock
import app.instaaddict.runner.core.AppLog
import kotlin.math.max
import kotlin.random.Random

/**
 * The run we did together in Termux: open Instagram, go to the Home feed, scroll slowly like a
 * person and like a handful of posts. Same idea as InstaAddict's `feed` job.
 */
object HomeFeedLikeTask : BotTask() {
    override val id = "home_feed_like"
    override val title = "Home feed: scroll & like"
    override val description =
        "Opens Instagram, scrolls your Home feed slowly like a person, and likes a few posts."
    override val emoji = "❤️"
    override val params = listOf(
        Param("likes_min", "Likes (at least)", 6, 1, 40),
        Param("likes_max", "Likes (at most)", 12, 1, 60),
        Param("delay_min", "Pause between actions (min)", 3, 1, 30, unit = "s"),
        Param("delay_max", "Pause between actions (max)", 8, 2, 60, unit = "s"),
        Param("max_minutes", "Stop after", 15, 2, 60, unit = "min"),
    )

    private const val MAX_SCROLLS = 80

    override suspend fun run(ctx: RunContext) {
        val likesMin = ctx.setting("likes_min")
        val likesMax = max(likesMin, ctx.setting("likes_max"))
        val target = ctx.randomInt(likesMin, likesMax)
        val delayMin = ctx.setting("delay_min") * 1000L
        val delayMax = max(delayMin, ctx.setting("delay_max") * 1000L)
        val deadline = SystemClock.elapsedRealtime() + ctx.setting("max_minutes") * 60_000L
        ctx.update { it.copy(target = target) }

        val ig = Ig(ctx)
        var nodes = ig.openAndGoHome()
        ctx.step("Scrolling your feed (goal: $target likes)")

        val decided = HashSet<String>()
        var likes = 0
        var seen = 0
        var ads = 0
        var scrolls = 0
        var likeFails = 0
        var sameScreen = 0

        while (likes < target) {
            ctx.checkCancelled()
            if (SystemClock.elapsedRealtime() > deadline) {
                ctx.finalNote = "Time limit reached after $likes like(s)."
                break
            }
            if (scrolls >= MAX_SCROLLS) {
                ctx.finalNote = "Scrolled a long way, so I stopped after $likes like(s)."
                break
            }
            if (ig.recoverIfNeeded(nodes)) {
                nodes = ig.dump()
                continue
            }
            val warning = ig.blockMessage(nodes)
            if (warning != null) {
                ctx.fail("Instagram showed a warning (\"$warning\"). I stopped to protect your account. Wait a day before trying again.")
            }
            if (ig.dismissPopup(nodes)) {
                ctx.pause(1200, 2000)
                nodes = ig.dump()
                continue
            }

            val pick = ig.likeTargets(nodes).firstOrNull { it.key !in decided }
            if (pick != null) {
                decided.add(pick.key)
                seen++
                ctx.update { it.copy(seen = seen) }
                if (pick.isAd) {
                    ads++
                    ctx.update { it.copy(adsSkipped = ads) }
                    AppLog.i("Run", "Skipped an ad")
                } else if (Random.nextInt(100) < 75) {
                    ig.tap(pick.node)
                    ctx.pause(1400, 2200)
                    val after = ig.dump()
                    if (ig.isLiked(after, pick.node)) {
                        likes++
                        likeFails = 0
                        ctx.update { it.copy(likes = likes) }
                        ctx.step("Liked a post by ${pick.name} ($likes/$target)")
                    } else {
                        likeFails++
                        AppLog.w("Run", "The heart on ${pick.name}'s post did not turn to Liked ($likeFails/3)")
                        if (likeFails >= 3) {
                            ctx.fail("The like button did not respond three times in a row. Check the Logs tab, and that Instagram is in English.")
                        }
                    }
                    nodes = after
                    if (likes >= target) break
                    ctx.pause(delayMin, delayMax)
                } else {
                    AppLog.i("Run", "Skipping ${pick.name}'s post on purpose, to look natural")
                }
                continue
            }

            // nothing new to like on this screen: scroll
            if (Random.nextInt(100) < 12) {
                ctx.step("Looking at a post for a moment")
                ctx.pause(6000, 12000)
            }
            val before = ig.signature(nodes)
            ig.scrollFeed()
            scrolls++
            ctx.update { it.copy(scrolls = scrolls) }
            ctx.pause((delayMin * 6) / 10, (delayMax * 6) / 10)
            nodes = ig.dump()
            if (ig.signature(nodes) == before) {
                sameScreen++
                if (sameScreen >= 3) {
                    ctx.finalNote = "The feed stopped moving, so I finished with $likes like(s)."
                    break
                }
            } else {
                sameScreen = 0
            }
        }

        if (likes == 0) {
            ctx.fail(
                "I scrolled but could not like anything. Make sure Instagram shows your Home feed with posts, " +
                    "is set to English, and the account is logged in. Check the Logs tab for details."
            )
        }
        if (ctx.finalNote.isBlank()) ctx.finalNote = "Liked $likes post(s) while scrolling the Home feed."
    }
}
