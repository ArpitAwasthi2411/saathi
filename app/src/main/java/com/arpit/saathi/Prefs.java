package com.arpit.saathi;

import android.content.Context;
import android.content.SharedPreferences;

public final class Prefs {
    public static final String DEFAULT_MODEL = "gemini-2.5-flash";
    public static final String DEFAULT_PROFILE =
            "Final-year B.Tech CSE (AI & DS) student at GHEC Bandla, Himachal Pradesh; also doing a BS in "
            + "Data Science from IIT Madras. Looking for a 6-month robotics internship for the 8th semester "
            + "(early 2027).\n"
            + "Skills: ROS 2 Humble, Nav2, SLAM (Cartographer, SLAM Toolbox), Gazebo, YOLO, PyTorch, ESP32 "
            + "firmware, Raspberry Pi, LLM integration.\n"
            + "Projects: Pathik, an LLM-augmented autonomous indoor mobile robot (RPLiDAR A1, Pi 4, ESP32-S3); "
            + "VestGuard, a fall-detection vest. First-author IEEE paper on YOLO pothole detection (OCIT 2025).\n"
            + "Goals: GATE DA 2027, then M.Tech in robotics (perception and planning for mobile robots).\n"
            + "Most interested in: autonomous mobile robots, SLAM, perception, planning, embodied AI, "
            + "assistive robotics.";

    private Prefs() {}

    public static SharedPreferences sp(Context c) {
        return c.getSharedPreferences("saathi", Context.MODE_PRIVATE);
    }

    public static String key(Context c) { return sp(c).getString("key", "").trim(); }

    public static String modelSort(Context c) { return model(sp(c).getString("modelSort", "")); }

    public static String modelResearch(Context c) { return model(sp(c).getString("modelResearch", "")); }

    private static String model(String m) {
        m = m == null ? "" : m.trim();
        if (m.startsWith("models/")) m = m.substring(7);
        return m.isEmpty() ? DEFAULT_MODEL : m;
    }

    public static String profile(Context c) {
        String p = sp(c).getString("profile", "");
        return p.trim().isEmpty() ? DEFAULT_PROFILE : p;
    }

    public static int digestHour(Context c) { return sp(c).getInt("digestHour", 20); }

    public static boolean wifiOnly(Context c) { return sp(c).getBoolean("wifiOnly", false); }
}
