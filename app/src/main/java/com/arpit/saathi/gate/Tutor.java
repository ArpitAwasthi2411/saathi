package com.arpit.saathi.gate;

import android.content.Context;

import com.arpit.saathi.Prefs;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** Prompts for the spoken GATE tutor: teaching a topic, talking through a question, or coaching overall. */
public final class Tutor {
    public static final String SPOKEN = "Your reply is read aloud by a phone voice and also shown on screen. "
            + "Talk like a sharp, friendly one-to-one tutor: natural spoken sentences, no markdown, no bullet "
            + "symbols, no LaTeX or maths symbols; say maths in words (\"x squared over two\", \"the inverse of "
            + "A\"). Keep each turn short, about 90 words at most, unless the student asks for a long "
            + "explanation. Usually end with one question for the student.";

    private static final String COMMANDS = "Actions you can take by adding a line at the very end of a reply "
            + "(each line is acted on and is not read aloud):\n"
            + "TASK: <short title> | <YYYY-MM-DD HH:MM, or none>   (when the student asks for a reminder)\n"
            + "LOG: <what they studied or did> | <minutes, or 0>   (when they tell you what they did)\n";

    private Tutor() {}

    private static String now() {
        return new SimpleDateFormat("yyyy-MM-dd HH:mm (EEEE)", Locale.US).format(new Date());
    }

    private static String history(List<String[]> turns) {
        StringBuilder sb = new StringBuilder();
        int from = Math.max(0, turns.size() - 12);
        for (int i = from; i < turns.size(); i++) {
            String[] t = turns.get(i);
            if (!t[0].isEmpty()) sb.append("Student: ").append(t[0]).append("\n");
            sb.append("Tutor: ").append(t[1]).append("\n");
        }
        return sb.toString();
    }

    private static String progress(Context c) {
        String r = GateWork.report(c);
        return r.length() > 5000 ? r.substring(0, 5000) + "\n…" : r;
    }

    /** Teach one syllabus topic as a conversation. */
    public static String topic(Context c, String key, List<String[]> turns, String said) {
        Gate.Topic t = GateDb.get(c).topic(key);
        int[] s = GateWork.topicStats(c, key);
        StringBuilder p = new StringBuilder();
        p.append("You are a GATE Data Science and AI tutor in a spoken revision session with your student.\n")
                .append("Now: ").append(now()).append("\nAbout the student:\n").append(Prefs.profile(c))
                .append("\n\nTOPIC: ").append(t.subject).append(" / ").append(t.name).append("\n");
        if (s[0] > 0) {
            p.append("In their mocks on this topic: ").append(s[1]).append(" right, ").append(s[2])
                    .append(" wrong, ").append(s[3]).append(" skipped.\n");
        }
        if (!t.notes.isEmpty()) {
            p.append("Their written notes on this topic (use them as the syllabus for the session):\n")
                    .append(t.notes.length() > 4000 ? t.notes.substring(0, 4000) + "…" : t.notes).append("\n");
        }
        p.append("\nHow to run the session: teach the topic as a dialogue, one idea per turn, building from ")
                .append("intuition to the exact result GATE needs. After each idea, check understanding with a ")
                .append("quick question (often a small numerical one). When they answer, say clearly whether it ")
                .append("is right and why, then go on. Spend extra time where their mocks show mistakes. If they ")
                .append("ask something, answer it, then return to the plan. When the topic is covered, give a ")
                .append("short recap and ask how confident they now feel: shaky, okay or strong. When they ")
                .append("answer that, also add the line  REVISED: <shaky|okay|strong>\n\n")
                .append(COMMANDS).append("\n").append(SPOKEN).append("\n\n");
        String h = history(turns);
        if (h.isEmpty()) p.append("Start the session now: one sentence on what you'll cover, then the first idea.\n");
        else p.append("SESSION SO FAR:\n").append(h).append("Student: ").append(said).append("\nTutor:");
        return p.toString();
    }

    /** Talk through one mock question, Socratic first. */
    public static String question(Context c, long qId, List<String[]> turns, String said) {
        Gate.Q q = GateDb.get(c).q(qId);
        StringBuilder p = new StringBuilder();
        p.append("You are a GATE Data Science and AI tutor talking a student through a question from their ")
                .append("mock, out loud.\n\n").append(q == null ? "" : GateWork.questionBlock(q));
        if (q != null && !q.solution.isEmpty()) {
            p.append("\nWritten solution (for you; don't read it out word for word):\n")
                    .append(q.solution.length() > 3500 ? q.solution.substring(0, 3500) + "…" : q.solution).append("\n");
        }
        p.append("\nGuide them to the answer: ask how they would start, give hints step by step, and only give the ")
                .append("full working if they ask or are stuck after two tries. If they got it wrong in the mock, ")
                .append("find the exact misunderstanding. Finish with the one thing to remember.\n\n")
                .append(COMMANDS).append("\n").append(SPOKEN).append("\n\n");
        String h = history(turns);
        if (h.isEmpty()) p.append("Start now: say what the question is about in one line and ask how they'd begin.\n");
        else p.append("SESSION SO FAR:\n").append(h).append("Student: ").append(said).append("\nTutor:");
        return p.toString();
    }

    // ---------------------------------------------------------------- mock review, one question at a time

    private static final String MARKING = "GATE marking: a wrong multiple-choice answer costs a third of its "
            + "marks (1-mark question: minus one third; 2-mark: minus two thirds). Multiple-select and numerical "
            + "questions have no negative marking. Use this when judging whether a skip or a guess was right.";

    /** The order a review goes in: wrong, then partial, then skipped (the marks you can win back). */
    public static List<Long> reviewOrder(Context c, long mockId) {
        List<Long> out = new java.util.ArrayList<>();
        String[] order = {"wrong", "partial", "unattempted"};
        List<Gate.Q> qs = GateDb.get(c).qs(mockId);
        for (String r : order) {
            for (Gate.Q q : qs) if (q.max > 0 && r.equals(q.result)) out.add(q.id);
        }
        return out;
    }

    public static String reviewIntro(Context c, long mockId, int count) {
        Gate.Mock m = GateDb.get(c).mock(mockId);
        StringBuilder byTopic = new StringBuilder();
        double lost = 0;
        for (Gate.Q q : GateDb.get(c).qs(mockId)) {
            if (q.max <= 0 || "correct".equals(q.result)) continue;
            lost += q.max - q.score;
            byTopic.append("- Q").append(q.n).append(" ").append(q.result).append(", ").append(GateWork.fmt(q.max))
                    .append(" marks, ").append(q.topic.isEmpty() ? "untagged" : q.topic).append("\n");
        }
        return "You are the student's GATE DA tutor starting a spoken review of one of their mocks.\n"
                + (m == null ? "" : "Mock: " + m.title + ". Score " + GateWork.fmt(m.score) + " out of "
                + GateWork.fmt(m.max) + " (" + Math.round(m.pct()) + "%). " + m.correct + " right, " + m.wrong
                + " wrong, " + m.partial + " partial, " + (m.questions - m.attempted) + " skipped.\n")
                + "Marks not earned: about " + GateWork.fmt(lost) + ".\nQuestions to review:\n" + byTopic + "\n"
                + MARKING + "\n\nIn about 80 words: say where the marks went (which topics or kinds of mistakes "
                + "cost the most), and that you'll now go through the " + count + " questions one by one, starting "
                + "with the wrong ones. Do not explain any question yet. Do not end with a question.\n\n" + SPOKEN;
    }

    public static String reviewQuestion(Context c, long qId, int k, int n) {
        Gate.Q q = GateDb.get(c).q(qId);
        if (q == null) return "Say: this question could not be found, moving on.";
        String verdict = "unattempted".equals(q.result) ? "skipped it" : "partial".equals(q.result)
                ? "got it partly right" : "got it wrong";
        return "You are the student's GATE DA tutor, reviewing their mock out loud, one question at a time. This is "
                + "question " + k + " of " + n + " in the review. The student " + verdict + ".\n\n"
                + GateWork.questionBlock(q)
                + (q.solution.isEmpty() ? "" : "\nWorked solution (for you; don't read it word for word):\n"
                + (q.solution.length() > 3000 ? q.solution.substring(0, 3000) + "…" : q.solution) + "\n")
                + "\n" + MARKING + "\n\nIn about 150 words, spoken: start with \"Question " + q.n + "\" and say in "
                + "one line what it asks. Then what went wrong: for a wrong or partial answer, the exact mistake "
                + "behind their answer; for a skip, whether it was worth attempting given the marking. Then the key "
                + "idea and the quickest correct method, with the answer. End with one line on how to get this "
                + "kind of question right next time, and the marks it is worth. Solve it yourself first; if the "
                + "official key looks wrong, say so. Do not ask a question at the end.\n\n" + SPOKEN;
    }

    public static String reviewFollowup(Context c, long qId, List<String[]> turns, String said) {
        Gate.Q q = GateDb.get(c).q(qId);
        return "You are the student's GATE DA tutor, reviewing a mock question out loud. They interrupted with a "
                + "question about it.\n\n" + (q == null ? "" : GateWork.questionBlock(q)) + "\nSO FAR:\n"
                + history(turns) + "Student: " + said + "\n\nAnswer them directly. Then say \"say next when "
                + "you're ready\". " + SPOKEN + "\nTutor:";
    }

    public static String reviewOutro(Context c, long mockId, int covered, int total) {
        StringBuilder topics = new StringBuilder();
        for (Gate.Q q : GateDb.get(c).qs(mockId)) {
            if (q.max > 0 && !"correct".equals(q.result) && !q.topic.isEmpty()) {
                topics.append(q.topic).append(" (").append(q.result).append(", ").append(GateWork.fmt(q.max))
                        .append(" marks); ");
            }
        }
        return "You are the student's GATE DA tutor finishing a spoken mock review. You went through " + covered
                + " of " + total + " questions. The questions they lost marks on, by topic: " + topics + "\n"
                + MARKING + "\n\nIn about 110 words: the two or three patterns behind the lost marks, the topics to "
                + "revise first, any change in exam strategy (for example guessing or skipping), and roughly how many "
                + "marks fixing these could add. Add at most two lines at the end of the form\n"
                + "TASK: Revise <topic> | none\nfor the most important topics.\n\n" + SPOKEN;
    }

    /** The GATE coach: knows the student's mocks, weak topics, revision and log. */
    public static String coach(Context c, List<String[]> turns, String said) {
        StringBuilder p = new StringBuilder();
        p.append("You are the student's personal GATE DA coach, in a spoken conversation. You know their ")
                .append("progress from the data below; use only that data for facts about them, and say so when ")
                .append("something isn't recorded.\nNow: ").append(now()).append("\nAbout the student:\n")
                .append(Prefs.profile(c)).append("\n\nTHEIR GATE DATA:\n").append(progress(c))
                .append("\nYou can: tell them how they're doing and what to focus on next; quiz them; teach any ")
                .append("syllabus topic they name, as a dialogue with check questions; plan their next study ")
                .append("session.\n\n").append(COMMANDS).append("\n").append(SPOKEN).append("\n\n");
        String h = history(turns);
        if (h.isEmpty()) {
            p.append("Start now: greet them in a few words, give the single most useful observation about their ")
                    .append("recent work, and ask what they want to do today.\n");
        } else {
            p.append("CONVERSATION SO FAR:\n").append(h).append("Student: ").append(said).append("\nCoach:");
        }
        return p.toString();
    }
}
