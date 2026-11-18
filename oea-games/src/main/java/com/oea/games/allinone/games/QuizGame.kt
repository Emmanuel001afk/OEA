package com.oea.games.allinone.games

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import com.oea.games.allinone.ArcadeBaseGame
import com.oea.games.allinone.ArcadeColors
import com.oea.games.allinone.ArcadeDraw
import com.oea.games.allinone.ArcadeTouch
import kotlin.math.min

/**
 * Manners & Interview: the questions life actually asks.
 *
 * Eight rounds move from everyday courtesy to the table, the phone, the office,
 * the first interview, the hard questions, the salary conversation and finally a
 * panel. Every answer explains itself — a wrong answer is still a lesson, which
 * is the point of the cabinet. The timer keeps a round moving; ten questions a
 * round, four options, one best answer.
 */
class QuizGame(private val track: Int = 0) : ArcadeBaseGame() {

    // Two cabinets share this engine: 0 = manners, etiquette and life with
    // people; 1 = the interview room and the working day. They are separate
    // games on the wall with separate levels and records.

    private data class Question(
        val text: String,
        val options: List<String>,
        val correct: Int,
        val why: String,
        val tier: Int,
    )

    private val bank = listOf(
        Question(
            "Someone arrives at your door and you are on the phone. What is the polite move?",
            listOf("Keep talking, they can wait", "End the call or ask the caller to hold, then greet your guest", "Wave them in and ignore them", "Leave the room without a word"),
            1, "Greet the person in front of you and give the caller a time: 'May I call you back in ten minutes?' Both deserve an answer.", 1
        ),
        Question(
            "A colleague's name is hard to pronounce. The respectful thing is to…",
            listOf("Give them a shorter name", "Avoid using their name", "Ask them how they say it, and use it", "Write it wrong in emails"),
            2, "Asking 'how do you say your name?' is a courtesy, not an admission. Then use it, every time.", 1
        ),
        Question(
            "You are late for a meeting. What do you do first?",
            listOf("Walk in late and explain later", "Send a short message before the start, then enter quietly", "Skip the meeting", "Blame traffic when you arrive"),
            1, "A message before you are late is respect for other people's time. Explaining as you enter costs the room attention twice.", 2
        ),
        Question(
            "At a formal dinner, where do the napkin and cutlery belong?",
            listOf("Napkin on the plate, fork in the right hand", "Napkin on your lap, forks on the left, knives on the right", "Everything on the right", "Napkin tucked into your collar"),
            1, "Napkin on the lap, forks left, knives and spoons right. Work from the outside in as courses arrive.", 2
        ),
        Question(
            "Your host serves a dish you do not eat. Best response?",
            listOf("Say it looks awful", "Eat a little and thank them, or explain your diet kindly in advance", "Push it around the plate silently", "Ask for something else loudly"),
            1, "Manners are about the guest's comfort too. A quiet 'thank you, I'll pass on this one' is enough; you owe no one a performance.", 2
        ),
        Question(
            "Someone calls during dinner. You should…",
            listOf("Answer and talk at the table", "Let it ring, or excuse yourself briefly if it is urgent", "Take the call on speaker", "Turn your phone off and blame the network"),
            1, "Excusing yourself is fine; taking the call at the table makes everyone else a listener.", 3
        ),
        Question(
            "In a shared workspace, a call comes in for you. You…",
            listOf("Take it at your desk on speaker", "Step away or keep it short and low", "Ignore the phone all day", "Ask a colleague to answer it"),
            1, "Shared space means shared ears. Step out for anything longer than a sentence.", 3
        ),
        Question(
            "A message to your manager should begin with…",
            listOf("The whole story, chronologically", "Who you are, what you need, and when", "A joke", "No greeting at all"),
            1, "Lead with the request and the deadline; detail follows. It respects the reader's time and gets an answer faster.", 3
        ),
        Question(
            "In an interview, 'Tell me about yourself' is really asking…",
            listOf("Your whole life story", "What you bring to this role, in under two minutes", "Why you left your last job", "Whether you are nervous"),
            1, "Give a short professional arc: what you do, one result you are proud of, and why this role fits. Then stop.", 5
        ),
        Question(
            "A question you cannot answer is best handled by…",
            listOf("Making something up", "Saying you do not know and describing how you would find out", "Changing the subject", "Blaming the question"),
            1, "Honesty plus method beats invention. Interviewers test judgement, not only knowledge.", 5
        ),
        Question(
            "What is the strongest answer to 'What is your weakness?'",
            listOf("'I have none'", "'I work too hard'", "A real weakness and the system you use to manage it", "A weakness that is really a strength"),
            2, "Name a genuine gap, then the habit that contains it. It reads as self-awareness rather than a slogan.", 6
        ),
        Question(
            "Why do you want this job? The answer should mention…",
            listOf("The salary only", "The role, the team and what you can contribute", "That you need any job", "That your friend works there"),
            1, "Interviewers listen for fit and intent. Salary is discussed later, at the right moment.", 6
        ),
        Question(
            "'What are your salary expectations?' is best answered by…",
            listOf("An exact number immediately, whatever they offer", "A researched range, tied to the role and your evidence", "Refusing to answer", "Asking them to guess"),
            1, "Research the band, state a range, and anchor it in the value you described earlier. Then let them respond.", 7
        ),
        Question(
            "After an interview, you should…",
            listOf("Wait silently for weeks", "Send a brief thank-you note the same or next day", "Call the manager repeatedly", "Post about it online"),
            1, "A short note that repeats one thing you discussed is remembered. Keep it to a few lines.", 7
        ),
        Question(
            "In a panel interview, when one panellist asks a question, you…",
            listOf("Answer only to that panellist", "Answer to the question, glancing at the whole panel", "Ignore the others", "Ask who is in charge"),
            1, "Speak to the whole panel. It is one conversation with several people, not several interviews at once.", 8
        ),
        Question(
            "You disagree with a colleague in front of a client. Best move?",
            listOf("Correct them sharply", "Support them now, settle it privately after", "Stay silent and let it fail", "Escalate immediately"),
            1, "Protect the team in public, correct the record in private. Trust survives that; it does not survive a public score-settling.", 4
        ),
        Question(
            "A friend tells you about a promotion. The first thing you say is…",
            listOf("'About time — what took so long?'", "'Congratulations — tell me about it.'", "'I would have got it.'", "Nothing"),
            1, "Let them have the moment. Curiosity is a gift; comparison is not.", 1
        ),
        Question(
            "You forgot a friend's birthday. The honest thing is…",
            listOf("Pretend you remembered", "Send a real apology and make time for them", "Wait until next year", "Blame your phone"),
            1, "A short, specific apology with a plan beats a fake surprise. People forgive forgetfulness, not dishonesty.", 1
        ),
        Question(
            "When you must interrupt a conversation, you say…",
            listOf("'Stop talking'", "'Sorry to cut in — may I add one point?'", "Nothing, talk over them", "Walk away"),
            1, "Ask for the floor with a reason. It names the interruption instead of pretending it did not happen.", 3
        ),
        Question(
            "A customer is angry and the fault is yours. You start with…",
            listOf("The policy that protects you", "An apology for the experience, then the fix", "Proof that they are wrong", "A transfer"),
            1, "Acknowledge the experience, then solve. Explanation belongs after the fix, not instead of it.", 4
        ),
        Question(
            "Sending a CV, the file name should be…",
            listOf("cv_final_final2.pdf", "Firstname Lastname — CV.pdf", "Download.pdf", "My Documents"),
            1, "A name and a purpose. The person receiving it will not remember what 'final2' contained.", 5
        ),
        Question(
            "You are asked to give a presentation tomorrow. First step?",
            listOf("Design 40 slides", "Write the one sentence the audience must remember", "Memorise every word", "Ask to postpone"),
            1, "One message first; slides serve it. If you cannot say it in a sentence, the deck will not say it either.", 5
        ),
        Question(
            "When a deadline will be missed, the professional move is…",
            listOf("Hide until it passes", "Tell the people affected early, with a new date and a plan", "Blame someone", "Deliver half without saying so"),
            1, "Bad news does not improve with age. Early notice lets others plan, and it is what trust is made of.", 7
        ),
        Question(
            "Someone gives you feedback you disagree with. You respond with…",
            listOf("'You are wrong'", "Questions, to understand the evidence", "Silence", "A complaint"),
            1, "Ask what they saw and what would look better. You can still disagree afterwards, with facts.", 6
        ),
        Question(
            "A thank-you after someone helps you should mention…",
            listOf("Nothing specific", "What they did and the difference it made", "That they owed you", "Only 'thanks'"),
            1, "Specific thanks names the act. It is short, sincere, and it lands.", 1
        ),
        Question(
            "Entering a room where people are already talking, you…",
            listOf("Start speaking immediately", "Greet, wait for a pause, then speak", "Stand silently until asked", "Leave"),
            1, "A greeting plus a pause respects the conversation already in progress.", 2
        ),
    )

    private var round = ArrayList<Question>()
    private var index = 0
    private var selected = -1
    private var answered = false
    private var correctCount = 0
    private var points = 0
    private var timeLeft = 20f
    private var optionRects = ArrayList<RectF>()
    private var nextRect = RectF()
    private var total = 10

    override fun onStart() {
        val tier = config.level.coerceIn(1, 8)
        val pool = bank.filter { if (track == 0) it.tier <= 4 else it.tier >= 5 }
        val ordered = pool.sortedBy { Math.abs(it.tier - tier) }.shuffled(random)
        round = ArrayList(ordered.take(total))
        index = 0
        selected = -1
        answered = false
        correctCount = 0
        points = 0
        timeLeft = (24f - config.level * 0.8f).coerceAtLeast(13f)
        optionRects.clear()
    }

    private val current: Question? get() = round.getOrNull(index)

    override fun update(dt: Float) {
        super.update(dt)
        if (done() || answered) return
        timeLeft -= dt
        if (timeLeft <= 0f) {
            answered = true
            selected = -1
            timeLeft = 0f
        }
    }

    override fun touch(phase: ArcadeTouch, x: Float, y: Float, width: Float, height: Float): Boolean {
        if (done() || phase != ArcadeTouch.DOWN) return false
        val question = current ?: return false
        if (!answered) {
            optionRects.forEachIndexed { option, rect ->
                if (ArcadeDraw.inside(rect, x, y)) {
                    selected = option
                    answered = true
                    if (option == question.correct) {
                        correctCount++
                        points += 10 + timeLeft.toInt() / 2
                    }
                    return true
                }
            }
            return false
        }
        if (ArcadeDraw.inside(nextRect, x, y)) {
            index++
            if (index >= round.size) {
                settle()
            } else {
                selected = -1
                answered = false
                timeLeft = (24f - config.level * 0.8f).coerceAtLeast(13f)
            }
            return true
        }
        return false
    }

    private fun settle() {
        val stars = when {
            correctCount >= 9 -> 3
            correctCount >= 7 -> 2
            else -> 1
        }
        finish(
            won = correctCount >= 6,
            headline = if (correctCount >= 9) "A polished round" else if (correctCount >= 6) "A solid round" else "Plenty to practise",
            detail = "$correctCount of ${round.size} correct. " +
                if (correctCount >= 9) "The next round moves to the harder interview questions." else "Replay the round and read the explanations — that is where the marks are.",
            score = points.toLong() + correctCount.toLong() * 10,
            stars = stars
        )
    }

    override fun status(): String {
        if (done()) return "Round over"
        val question = current ?: return ""
        return "Question ${index + 1}/${round.size} · $correctCount correct · ${timeLeft.toInt()}s"
    }

    override fun hint(): String = ""

    override fun score(): Long = points.toLong()

    override fun draw(canvas: Canvas, width: Float, height: Float) {
        canvas.drawColor(0xFF2C1E5C.toInt())
        val glow = ArcadeDraw.paint(Color.TRANSPARENT)
        glow.shader = android.graphics.RadialGradient(width * 0.8f, height * 0.15f, width,
            intArrayOf(0x559B7BEF, 0x002C1E5C), null, android.graphics.Shader.TileMode.CLAMP)
        canvas.drawRect(0f, 0f, width, height, glow)

        val question = current ?: return
        val top = height * 0.13f

        // Progress.
        ArcadeDraw.text(canvas, "QUESTION ${index + 1} OF ${round.size}", width * 0.06f, height * 0.075f, height * 0.014f,
            0xAAB9A8E8.toInt(), bold = true)
        ArcadeDraw.text(canvas, "${correctCount} CORRECT", width * 0.94f, height * 0.075f, height * 0.014f,
            0xFFB9F6E0.toInt(), bold = true, align = Paint.Align.RIGHT)
        val rail = RectF(width * 0.06f, height * 0.09f, width * 0.94f, height * 0.098f)
        ArcadeDraw.panel(canvas, rail, 0x33FFFFFF, rail.height() / 2f)
        ArcadeDraw.panel(canvas, RectF(rail.left, rail.top, rail.left + rail.width() * ((index + 1f) / round.size), rail.bottom),
            ArcadeColors.MINT, rail.height() / 2f)
        if (!answered) {
            val fraction = (timeLeft / (24f - config.level * 0.8f).coerceAtLeast(13f)).coerceIn(0f, 1f)
            ArcadeDraw.panel(canvas, RectF(width * 0.06f, height * 0.105f, width * 0.06f + width * 0.88f * fraction, height * 0.11f),
                if (fraction > 0.3f) ArcadeColors.GOLD else ArcadeColors.CORAL, height * 0.0025f)
        }

        // Question card.
        val questionBottom = ArcadeDraw.paragraph(canvas, question.text, width * 0.08f, top + height * 0.02f,
            height * 0.026f, Color.WHITE, width * 0.84f, bold = true, lineGap = 1.3f)
        var y = questionBottom + height * 0.025f
        optionRects.clear()
        val optionHeight = height * 0.068f
        question.options.forEachIndexed { option, label ->
            val rect = RectF(width * 0.06f, y, width * 0.94f, y + optionHeight)
            optionRects += rect
            val isCorrect = option == question.correct
            val isPicked = option == selected
            val fill = when {
                answered && isCorrect -> 0xFF2E9E76.toInt()
                answered && isPicked -> 0xFFB7384B.toInt()
                else -> 0x22FFFFFF
            }
            ArcadeDraw.panel(canvas, rect, fill, optionHeight * 0.28f,
                if (answered && isCorrect) 0xFF7BF0C4.toInt() else 0x22FFFFFF, 1.5f)
            ArcadeDraw.text(canvas, ('A' + option).toString(), rect.left + width * 0.06f, rect.centerY() + height * 0.007f,
                height * 0.02f, 0xCCFFFFFF.toInt(), bold = true)
            ArcadeDraw.paragraph(canvas, label, rect.left + width * 0.12f, rect.centerY() - height * 0.005f,
                height * 0.0165f, Color.WHITE, rect.width() - width * 0.16f, lineGap = 1.1f)
            y += optionHeight + height * 0.012f
        }

        if (answered) {
            val feedback = if (selected == question.correct) "Correct." else if (selected < 0) "The clock ran out." else "The best answer is ${('A' + question.correct).toString()}."
            val bottom = ArcadeDraw.paragraph(canvas, "$feedback ${question.why}", width * 0.06f, y + height * 0.012f,
                height * 0.016f, 0xFFE9DFFF.toInt(), width * 0.88f, lineGap = 1.3f)
            nextRect.set(width * 0.2f, min(bottom + height * 0.012f, height * 0.9f), width * 0.8f, min(bottom + height * 0.07f, height * 0.96f))
            ArcadeDraw.button(canvas, nextRect, if (index + 1 >= round.size) "See the result" else "Next question", ArcadeColors.MINT, ArcadeColors.INK)
        }
    }

}
