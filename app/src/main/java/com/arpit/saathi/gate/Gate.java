package com.arpit.saathi.gate;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** GATE DA data types and the syllabus everything is tagged against. */
public final class Gate {
    private Gate() {}

    public static final class Mock {
        public long id, created, taken;
        public String title = "", source = "pdf", status = "", progress = "", error = "";
        public int pages, questions, attempted, correct, wrong, partial;
        public double score, max;

        public double pct() { return max > 0 ? 100.0 * score / max : 0; }
        public boolean ready() { return "ready".equals(status); }
    }

    public static final class Q {
        public long id, mockId;
        public int n, page, pageTo;
        public double marks, score, max;
        public String type = "", question = "", options = "", yours = "", correct = "", result = "", subject = "",
                topic = "", solution = "", chat = "";
    }

    public static final class Topic {
        public String key = "", subject = "", name = "", notes = "", practice = "";
        public long revised;
        public int confidence; // 0 not set, 1 shaky, 2 okay, 3 strong
    }

    public static final class LogEntry {
        public long id, at;
        public int minutes;
        public String kind = "", text = "";
    }

    /** GATE 2027 DA syllabus (IIT Madras), section by section, plus General Aptitude. */
    public static final Map<String, String[]> SYLLABUS = new LinkedHashMap<>();

    static {
        SYLLABUS.put("Probability and Statistics", new String[]{
                "Counting: permutations and combinations",
                "Probability axioms, sample space, events, independence",
                "Conditional and joint probability, Bayes' theorem",
                "Descriptive statistics: mean, median, mode, variance, standard deviation",
                "Correlation and covariance",
                "Random variables, PMF, PDF, CDF, expectation, conditional expectation and variance",
                "Discrete distributions: uniform, Bernoulli, binomial, Poisson, geometric",
                "Continuous distributions: uniform, exponential, normal, t, chi-squared",
                "Central limit theorem",
                "Confidence intervals",
                "Hypothesis testing: z-test, t-test, chi-squared test"});
        SYLLABUS.put("Linear Algebra", new String[]{
                "Vector spaces, subspaces, linear dependence and independence",
                "Special matrices: projection, orthogonal, idempotent, partitioned",
                "Quadratic forms",
                "Systems of linear equations and Gaussian elimination",
                "Eigenvalues and eigenvectors",
                "Determinant, rank, nullity, projections",
                "LU decomposition",
                "Singular value decomposition"});
        SYLLABUS.put("Calculus and Optimization", new String[]{
                "Functions, limits, continuity and differentiability",
                "Taylor series",
                "Maxima and minima",
                "Optimization (including constrained problems)"});
        SYLLABUS.put("Programming, Data Structures and Algorithms", new String[]{
                "Programming in Python",
                "Stacks, queues and linked lists",
                "Trees and binary search trees",
                "Hash tables",
                "Linear and binary search",
                "Basic sorting: selection, bubble, insertion",
                "Divide and conquer: merge sort, quicksort",
                "Graphs: traversals and shortest paths"});
        SYLLABUS.put("Database Management and Warehousing", new String[]{
                "ER model and relational model",
                "Relational algebra and tuple calculus",
                "SQL",
                "Integrity constraints and normal forms",
                "File organization and indexing",
                "Data transformation: normalization, discretization, sampling, compression",
                "Data warehouse modelling: schemas, concept hierarchies, measures"});
        SYLLABUS.put("Machine Learning", new String[]{
                "Linear regression (simple, multiple, ridge)",
                "Logistic regression",
                "k-nearest neighbours",
                "Naive Bayes classifier",
                "Linear discriminant analysis",
                "Support vector machines",
                "Decision trees",
                "Bias-variance trade-off",
                "Cross-validation: leave-one-out, k-fold",
                "Neural networks: MLP, feed-forward",
                "Clustering: k-means, k-medoids, hierarchical, linkage",
                "Dimensionality reduction and PCA",
                "Gradient descent and its variants"});
        SYLLABUS.put("Artificial Intelligence", new String[]{
                "Search: uninformed, informed, adversarial",
                "Logic: propositional and predicate",
                "Reasoning under uncertainty: conditional independence, exact inference, sampling"});
        SYLLABUS.put("General Aptitude", new String[]{
                "Verbal aptitude",
                "Quantitative aptitude",
                "Analytical aptitude",
                "Spatial aptitude"});
    }

    public static String key(String subject, String topic) {
        return subject + " / " + topic;
    }

    public static List<String> allKeys() {
        List<String> out = new ArrayList<>();
        for (Map.Entry<String, String[]> e : SYLLABUS.entrySet()) {
            for (String t : e.getValue()) out.add(key(e.getKey(), t));
        }
        return out;
    }

    /** The syllabus as a numbered list a model can pick from. */
    public static String numbered() {
        StringBuilder sb = new StringBuilder();
        int n = 0;
        for (String k : allKeys()) sb.append(++n).append(". ").append(k).append("\n");
        return sb.toString();
    }

    public static String typeLabel(String type) {
        switch (type) {
            case "msq": return "multiple select";
            case "nat": return "numerical answer";
            default: return "multiple choice";
        }
    }
}
