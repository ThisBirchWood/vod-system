import { useEffect, useState } from "react";
import { buildGreetings, type GreetingContext } from "../../utils/greetings.ts";

type Props = Omit<GreetingContext, "now"> & {
    /** False until the clip list has loaded (or failed), so the line isn't picked from placeholder counts. */
    ready: boolean;
};

/**
 * A personalised welcome line, picked at random from a pool of greetings
 * once per visit. It is re-picked only if the user goes live or stops
 * streaming, so the line never contradicts the stream panel below it.
 */
const Greeting = ({ ready, ...context }: Props) => {
    const [line, setLine] = useState<string | null>(null);

    useEffect(() => {
        if (!ready) return;
        const lines = buildGreetings({ ...context, now: new Date() });
        setLine(lines[Math.floor(Math.random() * lines.length)]);
        // Deliberately not keyed on the rest of the context: clip counts
        // changing mid-visit shouldn't swap the greeting out.
        // eslint-disable-next-line react-hooks/exhaustive-deps
    }, [ready, context.isStreaming]);

    return (
        <div className="flex flex-col gap-1 min-w-0">
            <p className="font-data text-[11px] text-muted uppercase tracking-[0.12em]">
                {new Date().toLocaleDateString(undefined, { weekday: "long", month: "long", day: "numeric" })}
            </p>
            {/* min-h reserves the line's height while it loads, so the page doesn't jump. */}
            <h1 key={line} className="text-4xl font-heading text-text-primary leading-tight min-h-[1.25em] motion-safe:animate-fade-up">
                {line}
            </h1>
        </div>
    );
};

export default Greeting;
