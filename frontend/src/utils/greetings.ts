type GreetingContext = {
    firstName: string;
    now: Date;
    isStreaming: boolean;
    clipsToday: number;
    clipsThisWeek: number;
    totalClips: number;
    /** Title of the most recent clip, if it has one. */
    latestClipTitle: string | null;
};

const timeOfDay = (hour: number): "morning" | "afternoon" | "evening" | "night" => {
    if (hour >= 5 && hour < 12) return "morning";
    if (hour >= 12 && hour < 17) return "afternoon";
    if (hour >= 17 && hour < 22) return "evening";
    return "night";
};

/**
 * Builds the pool of welcome lines for the home page, tailored to the user's
 * name, the time of day and what they've been up to. The page rotates through
 * these, so every line should stand on its own.
 */
const buildGreetings = (ctx: GreetingContext): string[] => {
    const { firstName: name, now, isStreaming, clipsToday, clipsThisWeek, totalClips, latestClipTitle } = ctx;
    const period = timeOfDay(now.getHours());
    const weekday = now.toLocaleDateString(undefined, { weekday: "long" });

    // While live, keep the greeting about the stream.
    if (isStreaming) {
        return [
            `You're live, ${name}.`,
            `On air, ${name}. Drop a marker when something good happens.`,
            `Live and recording, ${name}.`,
        ];
    }

    const lines: string[] = [];

    switch (period) {
        case "morning":
            lines.push(`Good morning, ${name}.`);
            break;
        case "afternoon":
            lines.push(`Good afternoon, ${name}.`);
            break;
        case "evening":
            lines.push(`Good evening, ${name}.`);
            break;
        case "night":
            lines.push(`Working late, ${name}?`);
            break;
    }

    lines.push(`Welcome back, ${name}.`, `Happy ${weekday}, ${name}.`);

    if (totalClips === 0) {
        lines.push(`Ready to make your first clip, ${name}?`);
    } else if (clipsToday > 0) {
        lines.push(`${clipsToday} clip${clipsToday === 1 ? "" : "s"} made today.`);
    } else if (clipsThisWeek > 0) {
        lines.push(`${clipsThisWeek} clip${clipsThisWeek === 1 ? "" : "s"} this week.`);
    } else {
        lines.push(`No new clips this week.`);
    }

    if (latestClipTitle) lines.push(`Latest clip: “${latestClipTitle}”.`);

    return lines;
};

export { buildGreetings, timeOfDay };
export type { GreetingContext };
