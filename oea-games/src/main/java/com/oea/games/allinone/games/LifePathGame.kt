package com.oea.games.allinone.games

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import com.oea.games.allinone.ArcadeBaseGame
import com.oea.games.allinone.ArcadeColors
import com.oea.games.allinone.ArcadeDraw
import com.oea.games.allinone.ArcadeTouch
import kotlin.math.max
import kotlin.math.min

/**
 * Life Path: twelve months of a job you chose, with the choices that come with it.
 *
 * It is a work-and-life simulation, not a quiz: you pick a career, then each month
 * brings a situation with two or three honest options. Money, health, happiness,
 * skill and respect all move, and the feedback explains what the choice cost.
 * Halfway through there is a real interview — four questions, scored, with a
 * promotion and a pay rise on the line. Manners are part of the mechanics: how
 * you treat people changes what they offer you.
 */
class LifePathGame : ArcadeBaseGame() {

    private data class Job(
        val name: String,
        val glyph: String,
        val pay: Int,
        val skill: Int,
        val happy: Int,
        val note: String,
    )

    private data class Effect(
        val money: Int = 0,
        val health: Int = 0,
        val happy: Int = 0,
        val skill: Int = 0,
        val respect: Int = 0,
    )

    private data class Choice(val label: String, val effect: Effect, val reply: String)

    private data class Event(val title: String, val body: String, val choices: List<Choice>)

    private data class InterviewQ(val question: String, val options: List<String>, val correct: Int, val why: String)

    private enum class Stage { JOB, EVENT, FEEDBACK, INTERVIEW, INTERVIEW_DONE, MONTH_END }

    private val jobs = listOf(
        Job("Trader", "🛒", 120, 35, 60, "Fast money, thin margins, long market days."),
        Job("Teacher", "📚", 95, 55, 68, "Steady, respected, and every holiday earned."),
        Job("Doctor", "🩺", 180, 60, 55, "Long shifts, real stakes, deep respect."),
        Job("Engineer", "🔧", 150, 50, 62, "Build things that outlast the month."),
        Job("Chef", "🍲", 110, 45, 66, "Early mornings, creative nights."),
        Job("Footballer", "⚽", 140, 40, 72, "Short career, loud crowd, sharp discipline."),
    )

    private val events = listOf(
        Event(
            "The first rent",
            "Your first month is over and rent is due. Your cousin needs a loan for school fees the same week.",
            listOf(
                Choice("Pay rent first, then offer a small honest loan", Effect(money = -220, respect = 8, happy = 2),
                    "You protected your home and still helped. Your cousin understood the limit because you named it."),
                Choice("Pay the cousin first — rent can wait a week", Effect(money = -300, respect = 4, health = -3),
                    "Kind, but the landlord does not care about kindness. Being reliable is part of generosity."),
                Choice("Say no to both and keep the money", Effect(money = 60, respect = -10, happy = -4),
                    "Your money is safe and your name took the damage. People remember who disappeared when they asked."),
            )
        ),
        Event(
            "A late customer",
            "A customer arrives five minutes before closing, knowing exactly what they want.",
            listOf(
                Choice("Serve them properly, close a little late", Effect(money = 40, respect = 6, health = -2),
                    "They told three people about you. A small act of service travels further than an advert."),
                Choice("Serve them quickly and hurry them out", Effect(money = 25, respect = -2),
                    "You got the sale and lost the warmth. Speed is a service; rushing is not."),
                Choice("Refuse — the sign says closed", Effect(respect = -5),
                    "The sign was right and the customer still left with a story about the shop."),
            )
        ),
        Event(
            "A mistake on the books",
            "You find a mistake you made last week. Nobody has noticed yet.",
            listOf(
                Choice("Report it, with the fix attached", Effect(skill = 8, respect = 12, happy = 2),
                    "Owning an error with a solution is the fastest way to be trusted with more."),
                Choice("Fix it quietly and say nothing", Effect(skill = 3, respect = 2),
                    "The books are right, the trust is untested. Quiet fixes are fine until the next mistake is bigger."),
                Choice("Hide it until someone asks", Effect(respect = -14, happy = -6),
                    "The error is small; the silence is not. People forgive arithmetic, rarely concealment."),
            )
        ),
        Event(
            "A difficult colleague",
            "A colleague takes credit for your work in front of the manager.",
            listOf(
                Choice("Speak to them privately first", Effect(respect = 6, skill = 2),
                    "You gave them a chance to correct it without an audience. Most people take it."),
                Choice("Correct it in the meeting, calmly, with evidence", Effect(respect = 10, skill = 3),
                    "Direct and factual in public is acceptable when the record is wrong. Keep it short and unemotional."),
                Choice("Say nothing and resent it", Effect(happy = -8, respect = -4),
                    "Silence is read as agreement. The work stays yours and the reputation does not."),
            )
        ),
        Event(
            "The extra shift",
            "There is extra work this week. It pays, but you have not slept properly in days.",
            listOf(
                Choice("Take it and plan a proper rest after", Effect(money = 150, health = -6, skill = 4),
                    "You earned and you scheduled the recovery. A plan makes the sacrifice temporary."),
                Choice("Take it and keep pushing", Effect(money = 200, health = -14, happy = -4),
                    "The money arrived and your body sent an invoice. Health is the one stat that is expensive to rebuild."),
                Choice("Decline and protect your rest", Effect(health = 8, happy = 3, money = -20),
                    "A rested worker on Monday is worth more than an exhausted one on Saturday."),
            )
        ),
        Event(
            "Kindness in the queue",
            "An elderly customer is struggling with a phone payment and the queue is impatient.",
            listOf(
                Choice("Step out and help them personally", Effect(respect = 12, happy = 6, money = -10, skill = 3),
                    "You lost a few minutes and set the tone of the whole room. Manners are visible work."),
                Choice("Point at the sign and keep moving", Effect(respect = -6, happy = -2, money = 5),
                    "The queue moved. The person did not. Neither will the story they tell."),
                Choice("Ask a colleague to help while you serve", Effect(respect = 8, happy = 3),
                    "Delegation with care: the customer is helped and the queue is kept moving."),
            )
        ),
        Event(
            "The friend's wedding",
            "A close friend's wedding falls on your busiest working weekend.",
            listOf(
                Choice("Tell them early and help in another way", Effect(happy = 5, respect = 6, money = -30),
                    "Honesty in advance is a gift. They remember who told them the truth early."),
                Choice("Attend anyway and let work suffer", Effect(happy = 10, respect = 2, money = -60, skill = -2),
                    "The day was worth it. The work will ask for it back on Monday."),
                Choice("Say nothing and not show up", Effect(happy = -10, respect = -12),
                    "The missed wedding is a day; the silence is a wound."),
            )
        ),
        Event(
            "A cheaper supplier",
            "A supplier offers a cheaper price if you skip the receipt and pay in cash.",
            listOf(
                Choice("Refuse and keep the proper supplier", Effect(money = -40, respect = 10),
                    "You kept the books clean and your name clean. That is the price and it is worth it."),
                Choice("Take the deal quietly", Effect(money = 120, respect = -8, skill = -2),
                    "The margin improved and the risk did not disappear. Unreceipted money has a way of finding you."),
                Choice("Ask the proper supplier to match the price", Effect(money = 40, respect = 4, skill = 5),
                    "Negotiation is a skill, not a compromise. You kept the paper trail and the discount."),
            )
        ),
        Event(
            "The promotion rumour",
            "A colleague says a supervisor role is opening. They are also applying.",
            listOf(
                Choice("Apply openly and tell them you are", Effect(skill = 6, respect = 8, happy = 2),
                    "Transparency removes the ambush. Two people can compete and still work together."),
                Choice("Apply quietly and avoid the subject", Effect(skill = 3, respect = -3),
                    "Neutral. Nobody can accuse you of lying, and nobody can trust you either."),
                Choice("Withdraw because they need it more", Effect(respect = -2, happy = -6, money = -30),
                    "Kindness that costs you your own path is not kindness; it is avoidance."),
            )
        ),
        Event(
            "A tired team",
            "Your team is exhausted and the deadline is close.",
            listOf(
                Choice("Cut the scope, tell the client honestly", Effect(respect = 10, skill = 5, money = -30, happy = 4),
                    "An honest smaller delivery beats a late bigger one. Clients remember who warned them."),
                Choice("Push everyone harder", Effect(money = 30, health = -5, happy = -8, respect = -6),
                    "The deadline was met and the team was spent. The next sprint starts with less than it needs."),
                Choice("Do the extra work yourself overnight", Effect(health = -10, happy = -3, skill = 4, respect = 4),
                    "Heroic, and not repeatable. A system that needs a hero every week is a broken system."),
            )
        ),
        Event(
            "The thank-you",
            "Someone who mentored you years ago asks for a favour at short notice.",
            listOf(
                Choice("Make time, and be honest about the limit", Effect(respect = 10, happy = 5, money = -20),
                    "You repaid a debt without pretending to be unlimited. That is what gratitude looks like."),
                Choice("Say yes to everything", Effect(happy = -5, health = -4, respect = 4),
                    "Generous and unsustainable. A promise you cannot keep is worse than a smaller one you can."),
                Choice("Say you are too busy", Effect(respect = -6, happy = -4),
                    "Sometimes the answer is no. The way you say it decides what remains of the relationship."),
            )
        ),
        Event(
            "Paying it forward",
            "You have a little extra this month and a junior colleague cannot afford transport.",
            listOf(
                Choice("Cover it quietly, no conditions", Effect(money = -60, respect = 10, happy = 8),
                    "They will remember you never mentioned it. Dignity is part of the help."),
                Choice("Offer to teach them something that pays", Effect(respect = 8, skill = 4, happy = 4),
                    "A skill outlasts a fare. Doing both is the best answer, when you can."),
                Choice("Keep the money — you earned it", Effect(money = 60, happy = -2),
                    "Nothing wrong with keeping what you earned. Life Path simply notes that some months give you a choice."),
            )
        ),
    )

    private val interview = listOf(
        InterviewQ(
            "Tell me about yourself.",
            listOf(
                "My whole life story from school",
                "What I do, one result I am proud of, and why I want this role",
                "That I have no experience but I am willing",
                "A joke to break the ice",
            ),
            1, "Two minutes, professional arc, then stop. The panel can ask for more."
        ),
        InterviewQ(
            "Why do you want this role?",
            listOf(
                "The salary",
                "The role, the team and what I bring",
                "Because I need any job",
                "My friend works here",
            ),
            1, "Fit and intent are what is being tested; money has its own conversation later."
        ),
        InterviewQ(
            "You notice a mistake in the numbers at the last minute. What do you do?",
            listOf(
                "Submit and fix it later quietly",
                "Flag it immediately with the correction you have started",
                "Ask someone else to sign it",
                "Ignore it — it is small",
            ),
            1, "The judgement being tested is honesty plus a solution, not fear."
        ),
        InterviewQ(
            "What are your salary expectations?",
            listOf(
                "Anything you offer",
                "An exact number, no matter the role",
                "A researched range tied to the role and my evidence",
                "Ask the panel to say their number first",
            ),
            2, "A researched range shows preparation and a fair, professional self-assessment."
        ),
    )

    private var stage = Stage.JOB
    private var jobIndex = -1
    private var money = 1200
    private var health = 80
    private var happy = 65
    private var skillPoints = 40
    private var respect = 50
    private var month = 1
    private var eventIndex = 0
    private var current: Event? = null
    private var feedback = ""
    private var interviewIndex = 0
    private var interviewCorrect = 0
    private var interviewDone = false
    private var interviewAnswered = -1
    private var pay = 100
    private var buttons = ArrayList<Pair<RectF, Int>>()
    private var moneyThisMonth = 0

    private val totalMonths = 12

    override fun onStart() {
        stage = Stage.JOB
        jobIndex = -1
        money = 900 + config.level * 60
        health = 80
        happy = 65
        skillPoints = 40
        respect = 50
        month = 1
        eventIndex = 0
        current = null
        feedback = ""
        interviewIndex = 0
        interviewCorrect = 0
        interviewDone = false
        interviewAnswered = -1
        pay = 100
        moneyThisMonth = 0
        buttons.clear()
    }

    private fun chooseJob(index: Int) {
        jobIndex = index
        val job = jobs[index]
        pay = (job.pay * (0.85f + config.level * 0.03f)).toInt()
        skillPoints = job.skill
        happy = job.happy
        respect = 45 + config.level
        money = 700 + config.level * 70
        stage = Stage.EVENT
        pickEvent()
    }

    private fun pickEvent() {
        current = events[((month - 1) * 2 + jobIndex) % events.size]
        stage = Stage.EVENT
        buttons.clear()
    }

    private fun apply(effect: Effect) {
        val costScale = 1f + (config.level - 1) * 0.07f
        money += if (effect.money < 0) (effect.money * costScale).toInt() else effect.money
        health = (health + effect.health).coerceIn(0, 100)
        happy = (happy + effect.happy).coerceIn(0, 100)
        skillPoints = (skillPoints + effect.skill).coerceIn(0, 100)
        respect = (respect + effect.respect).coerceIn(0, 100)
    }

    private fun monthIncome() {
        val earned = (pay * (0.75f + skillPoints / 160f) * (0.9f + config.level * 0.05f)).toInt()
        money += earned
        moneyThisMonth = earned
        money -= (140 + config.level * 24)
        if (health < 20) happy = (happy - 6).coerceAtLeast(0)
    }

    private fun advanceMonth() {
        monthIncome()
        month++
        if (month > totalMonths) {
            settle()
            return
        }
        if (month == 7 && !interviewDone) {
            interviewIndex = 0
            interviewCorrect = 0
            interviewAnswered = -1
            stage = Stage.INTERVIEW
            return
        }
        stage = Stage.EVENT
        pickEvent()
    }

    private fun settle() {
        val score = (money / 8).coerceAtLeast(0) + happy * 4 + skillPoints * 3 + respect * 2 + health
        val won = money >= 0 && happy >= 50 && respect >= 45 && health > 0
        val stars = when {
            won && score > 750 -> 3
            won -> 2
            else -> 1
        }
        finish(
            won = won,
            headline = when {
                !won -> "A hard year, honestly lived"
                score > 750 -> "The good life, earned"
                else -> "Twelve months, still standing"
            },
            detail = "Money ₦$money · happiness $happy · health $health · skill $skillPoints · respect $respect. " +
                if (won) "The next Life Path asks more of you: bigger bills, the same choices." else "Money and happiness are not the same meter — the next run can trade one for the other deliberately.",
            score = score.toLong(),
            stars = stars
        )
    }

    // ------------------------------------------------------------------- input

    override fun touch(phase: ArcadeTouch, x: Float, y: Float, width: Float, height: Float): Boolean {
        if (done() || phase != ArcadeTouch.DOWN) return false
        when (stage) {
            Stage.JOB -> {
                buttons.forEach { (rect, index) ->
                    if (ArcadeDraw.inside(rect, x, y)) {
                        chooseJob(index)
                        return true
                    }
                }
            }
            Stage.EVENT -> {
                val event = current ?: return false
                buttons.forEach { (rect, index) ->
                    if (ArcadeDraw.inside(rect, x, y)) {
                        val choice = event.choices.getOrNull(index) ?: return false
                        apply(choice.effect)
                        feedback = choice.reply
                        stage = Stage.FEEDBACK
                        buttons.clear()
                        return true
                    }
                }
            }
            Stage.FEEDBACK -> {
                if (buttons.any { ArcadeDraw.inside(it.first, x, y) }) {
                    advanceMonth()
                    return true
                }
            }
            Stage.INTERVIEW -> {
                if (interviewAnswered >= 0) {
                    if (interviewIndex + 1 >= interview.size) {
                        val bonus = interviewCorrect * 8
                        skillPoints = (skillPoints + bonus).coerceAtLeast(0)
                        pay += if (interviewCorrect >= 3) 45 else if (interviewCorrect >= 2) 20 else 0
                        respect = (respect + interviewCorrect * 3).coerceAtLeast(0)
                        interviewDone = true
                        stage = Stage.INTERVIEW_DONE
                        buttons.clear()
                    } else {
                        interviewIndex++
                        interviewAnswered = -1
                        buttons.clear()
                    }
                    return true
                }
                buttons.forEach { (rect, index) ->
                    if (ArcadeDraw.inside(rect, x, y)) {
                        interviewAnswered = index
                        if (index == interview[interviewIndex].correct) interviewCorrect++
                        return true
                    }
                }
            }
            Stage.INTERVIEW_DONE -> {
                if (buttons.any { ArcadeDraw.inside(it.first, x, y) }) {
                    stage = Stage.EVENT
                    pickEvent()
                    return true
                }
            }
        }
        return true
    }

    override fun update(dt: Float) {
        super.update(dt)
    }

    override fun status(): String {
        if (done()) return "Year complete"
        val job = jobs.getOrNull(jobIndex)?.name ?: "Choose a job"
        return "Month $month/$totalMonths · $job · ₦$money · respect $respect"
    }

    override fun hint(): String = when (stage) {
        Stage.JOB -> "Choose the work you want to live for twelve months."
        Stage.EVENT -> "There is no perfect answer; each one costs something."
        Stage.FEEDBACK -> "Read what the choice cost, then move to the next month."
        Stage.INTERVIEW -> "This is a real interview. Answer the way a good panel would want."
        else -> ""
    }

    override fun score(): Long = (money / 8).coerceAtLeast(0).toLong() + happy + respect

    // -------------------------------------------------------------------- draw

    override fun draw(canvas: Canvas, width: Float, height: Float) {
        canvas.drawColor(0xFF14332C.toInt())
        val glow = ArcadeDraw.paint(Color.TRANSPARENT)
        glow.shader = android.graphics.RadialGradient(width * 0.2f, height * 0.1f, width,
            intArrayOf(0x3343D6A2, 0x0014332C), null, android.graphics.Shader.TileMode.CLAMP)
        canvas.drawRect(0f, 0f, width, height, glow)
        buttons.clear()
        drawStats(canvas, width, height)
        when (stage) {
            Stage.JOB -> drawJobs(canvas, width, height)
            Stage.EVENT -> drawEvent(canvas, width, height)
            Stage.FEEDBACK -> drawFeedback(canvas, width, height)
            Stage.INTERVIEW -> drawInterview(canvas, width, height)
            Stage.INTERVIEW_DONE -> drawInterviewDone(canvas, width, height)
            Stage.MONTH_END -> Unit
        }
    }

    private fun drawStats(canvas: Canvas, width: Float, height: Float) {
        val top = height * 0.035f
        ArcadeDraw.panel(canvas, RectF(width * 0.04f, top, width * 0.96f, top + height * 0.13f), 0x33000000, height * 0.018f)
        ArcadeDraw.text(canvas, jobs.getOrNull(jobIndex)?.let { "${it.glyph} ${it.name}" } ?: "LIFE PATH",
            width * 0.07f, top + height * 0.03f, height * 0.018f, Color.WHITE, bold = true)
        ArcadeDraw.text(canvas, "MONTH $month/$totalMonths", width * 0.93f, top + height * 0.03f, height * 0.014f,
            ArcadeColors.MINT, bold = true, align = Paint.Align.RIGHT)
        val stats = listOf(
            "₦$money" to ArcadeColors.GOLD,
            "♥ $health" to ArcadeColors.CORAL,
            "☺ $happy" to 0xFFFFD34D.toInt(),
            "★ $skillPoints" to ArcadeColors.SKY,
            "✋ $respect" to ArcadeColors.LILAC,
        )
        var x = width * 0.07f
        val y = top + height * 0.088f
        val cell = width * 0.88f / stats.size
        stats.forEach { (label, color) ->
            ArcadeDraw.panel(canvas, x, y - height * 0.018f, x + cell - width * 0.012f, y + height * 0.02f,
                Color.argb(40, Color.red(color), Color.green(color), Color.blue(color)), height * 0.012f)
            ArcadeDraw.text(canvas, label, x + (cell - width * 0.012f) / 2f, y + height * 0.008f, height * 0.0135f,
                color, bold = true, align = Paint.Align.CENTER)
            x += cell
        }
    }

    private fun card(canvas: Canvas, width: Float, height: Float): RectF {
        val rect = RectF(width * 0.05f, height * 0.19f, width * 0.95f, height * 0.82f)
        ArcadeDraw.panel(canvas, rect, 0xFFFFFBF4.toInt(), height * 0.022f)
        return rect
    }

    private fun drawJobs(canvas: Canvas, width: Float, height: Float) {
        val rect = card(canvas, width, height)
        ArcadeDraw.text(canvas, "CHOOSE YOUR WORK", rect.left + width * 0.05f, rect.top + height * 0.05f,
            height * 0.017f, ArcadeColors.INK_SOFT, bold = true)
        ArcadeDraw.text(canvas, "Twelve months. Real choices. Manners count.", rect.left + width * 0.05f,
            rect.top + height * 0.085f, height * 0.02f, ArcadeColors.INK, bold = true)
        val cols = 2
        val rows = 3
        val gap = width * 0.02f
        val cardW = (rect.width() - width * 0.1f - gap) / cols
        val cardH = (rect.height() - height * 0.16f - gap * 2) / rows
        jobs.forEachIndexed { index, job ->
            val col = index % cols
            val row = index / cols
            val l = rect.left + width * 0.05f + col * (cardW + gap)
            val t = rect.top + height * 0.12f + row * (cardH + gap)
            val r = RectF(l, t, l + cardW, t + cardH)
            buttons += r to index
            ArcadeDraw.panel(canvas, r, if (index % 2 == 0) 0xFFF3FBF7.toInt() else 0xFFFFF7EC.toInt(), height * 0.016f, 0xFFE2D5C3.toInt(), 1f)
            ArcadeDraw.text(canvas, job.glyph, r.left + width * 0.06f, r.top + cardH * 0.5f, height * 0.03f, ArcadeColors.INK)
            ArcadeDraw.text(canvas, job.name, r.left + width * 0.16f, r.top + cardH * 0.32f, height * 0.016f, ArcadeColors.INK, bold = true)
            ArcadeDraw.text(canvas, "₦${job.pay}/mo", r.left + width * 0.16f, r.top + cardH * 0.52f, height * 0.013f, 0xFF8A5A12.toInt(), bold = true)
            ArcadeDraw.text(canvas, job.note, r.left + width * 0.05f, r.top + cardH * 0.8f, height * 0.011f, ArcadeColors.INK_SOFT)
        }
    }

    private fun drawEvent(canvas: Canvas, width: Float, height: Float) {
        val rect = card(canvas, width, height)
        val event = current ?: return
        ArcadeDraw.text(canvas, "MONTH $month · A CHOICE", rect.left + width * 0.05f, rect.top + height * 0.05f,
            height * 0.015f, ArcadeColors.MINT, bold = true)
        ArcadeDraw.text(canvas, event.title, rect.left + width * 0.05f, rect.top + height * 0.09f, height * 0.025f,
            ArcadeColors.INK, bold = true)
        var y = ArcadeDraw.paragraph(canvas, event.body, rect.left + width * 0.05f, rect.top + height * 0.13f,
            height * 0.018f, ArcadeColors.INK_SOFT, rect.width() - width * 0.1f, lineGap = 1.3f)
        y += height * 0.015f
        val buttonH = height * 0.075f
        event.choices.forEachIndexed { index, choice ->
            val b = RectF(rect.left + width * 0.05f, y, rect.right - width * 0.05f, y + buttonH)
            buttons += b to index
            ArcadeDraw.panel(canvas, b, if (index == 0) 0xFFEAF7F1.toInt() else 0xFFFFF7EC.toInt(),
                buttonH * 0.3f, if (index == 0) ArcadeColors.MINT else 0xFFE2D5C3.toInt(), 1.2f)
            ArcadeDraw.text(canvas, (index + 1).toString(), b.left + width * 0.045f, b.centerY() + height * 0.007f,
                height * 0.017f, ArcadeColors.INK_SOFT, bold = true)
            ArcadeDraw.paragraph(canvas, choice.label, b.left + width * 0.1f, b.centerY() - height * 0.004f,
                height * 0.0155f, ArcadeColors.INK, b.width() - width * 0.14f, lineGap = 1.08f)
            y += buttonH + height * 0.012f
        }
        ArcadeDraw.text(canvas, "Every choice changes money, health, happiness, skill or respect.",
            rect.centerX(), rect.bottom - height * 0.025f, height * 0.0115f, 0xFF9C8F7D.toInt(), align = Paint.Align.CENTER)
    }

    private fun drawFeedback(canvas: Canvas, width: Float, height: Float) {
        val rect = card(canvas, width, height)
        ArcadeDraw.text(canvas, "WHAT HAPPENED", rect.left + width * 0.05f, rect.top + height * 0.055f,
            height * 0.015f, ArcadeColors.MINT, bold = true)
        val end = ArcadeDraw.paragraph(canvas, feedback, rect.left + width * 0.05f, rect.top + height * 0.1f,
            height * 0.02f, ArcadeColors.INK, rect.width() - width * 0.1f, lineGap = 1.35f)
        ArcadeDraw.paragraph(canvas,
            "Your month closes: income ₦$moneyThisMonth, bills paid. The path you are building shows in your stat line at the top.",
            rect.left + width * 0.05f, end + height * 0.03f, height * 0.0145f, ArcadeColors.INK_SOFT,
            rect.width() - width * 0.1f, lineGap = 1.3f)
        val b = RectF(rect.centerX() - rect.width() * 0.32f, rect.bottom - height * 0.11f,
            rect.centerX() + rect.width() * 0.32f, rect.bottom - height * 0.035f)
        buttons += b to 0
        ArcadeDraw.button(canvas, b, if (month >= totalMonths) "Finish the year" else "Next month", ArcadeColors.INK, Color.WHITE)
    }

    private fun drawInterview(canvas: Canvas, width: Float, height: Float) {
        val rect = card(canvas, width, height)
        val question = interview[interviewIndex]
        ArcadeDraw.text(canvas, "THE INTERVIEW · ${interviewIndex + 1}/${interview.size}", rect.left + width * 0.05f,
            rect.top + height * 0.05f, height * 0.015f, ArcadeColors.LILAC, bold = true)
        val end = ArcadeDraw.paragraph(canvas, question.question, rect.left + width * 0.05f, rect.top + height * 0.1f,
            height * 0.023f, ArcadeColors.INK, rect.width() - width * 0.1f, bold = true, lineGap = 1.3f)
        var y = end + height * 0.015f
        val h = height * 0.062f
        question.options.forEachIndexed { index, option ->
            val b = RectF(rect.left + width * 0.05f, y, rect.right - width * 0.05f, y + h)
            buttons += b to index
            val correct = index == question.correct
            val chosen = index == interviewAnswered
            val fill = when {
                interviewAnswered >= 0 && correct -> 0xFFEAF7F1.toInt()
                interviewAnswered >= 0 && chosen -> 0xFFFDEAEC.toInt()
                else -> 0xFFFFF7EC.toInt()
            }
            ArcadeDraw.panel(canvas, b, fill, h * 0.3f,
                if (interviewAnswered >= 0 && correct) ArcadeColors.MINT else 0xFFE2D5C3.toInt(), 1.2f)
            ArcadeDraw.paragraph(canvas, option, b.left + width * 0.04f, b.centerY() - height * 0.004f,
                height * 0.0145f, ArcadeColors.INK, b.width() - width * 0.08f, lineGap = 1.05f)
            y += h + height * 0.01f
        }
        if (interviewAnswered >= 0) {
            val bottom = ArcadeDraw.paragraph(canvas, question.why, rect.left + width * 0.05f, min(y, rect.bottom - height * 0.12f),
                height * 0.014f, ArcadeColors.INK_SOFT, rect.width() - width * 0.1f, lineGap = 1.2f)
            val b = RectF(rect.centerX() - rect.width() * 0.3f, min(bottom + height * 0.01f, rect.bottom - height * 0.075f),
                rect.centerX() + rect.width() * 0.3f, min(bottom + height * 0.06f, rect.bottom - height * 0.015f))
            buttons += b to 0
            ArcadeDraw.button(canvas, b, if (interviewIndex + 1 >= interview.size) "Hear the panel" else "Next question",
                ArcadeColors.LILAC, Color.WHITE)
        }
    }

    private fun drawInterviewDone(canvas: Canvas, width: Float, height: Float) {
        val rect = card(canvas, width, height)
        ArcadeDraw.text(canvas, "THE PANEL'S DECISION", rect.left + width * 0.05f, rect.top + height * 0.06f,
            height * 0.015f, ArcadeColors.MINT, bold = true)
        ArcadeDraw.text(canvas, if (interviewCorrect >= 3) "Promotion" else if (interviewCorrect >= 2) "Kept on, with a rise" else "Kept on",
            rect.left + width * 0.05f, rect.top + height * 0.13f, height * 0.032f, ArcadeColors.INK, bold = true)
        val text = "You answered $interviewCorrect of ${interview.size} the way the panel wanted. " +
            if (interviewCorrect >= 3) "The role is yours and the pay rises. Skill +${interviewCorrect * 8}. The second half of the year starts with more weight on your shoulders."
            else if (interviewCorrect >= 2) "A small rise and a note that your answers were solid. Skill +${interviewCorrect * 8}. Prepare the salary question next time."
            else "The role stays as it is. Skill +${interviewCorrect * 8}. Read the explanations — an interview is a skill like any other."
        val end = ArcadeDraw.paragraph(canvas, text, rect.left + width * 0.05f, rect.top + height * 0.2f,
            height * 0.019f, ArcadeColors.INK_SOFT, rect.width() - width * 0.1f, lineGap = 1.35f)
        val b = RectF(rect.centerX() - rect.width() * 0.3f, max(end + height * 0.03f, rect.bottom - height * 0.1f),
            rect.centerX() + rect.width() * 0.3f, max(end + height * 0.03f, rect.bottom - height * 0.1f) + height * 0.07f)
        buttons += b to 0
        ArcadeDraw.button(canvas, b, "Back to work", ArcadeColors.INK, Color.WHITE)
    }
}
