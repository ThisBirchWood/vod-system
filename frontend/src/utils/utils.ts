function formatTime(seconds: number): string {
    const h = Math.floor(seconds / 3600);
    const m = Math.floor((seconds % 3600) / 60);
    const s = Math.floor(seconds % 60);
    const pad = (n: number) => n.toString().padStart(2, '0');
    if (h > 0) return `${h}:${pad(m)}:${pad(s)}`;
    return `${m}:${pad(s)}`;
}

// Parses a UTC date string, appending Z if no timezone offset is present so
// the browser always interprets the value as UTC rather than local time.
function stringToDate(dateString: string): Date {
    const normalized = dateString.replace(/(\.\d{3})\d+/, "$1");
    const withTz = /[Zz]$|[+-]\d{2}:\d{2}$|[+-]\d{4}$/.test(normalized) ? normalized : `${normalized}Z`;
    const date = new Date(withTz);
    if (isNaN(date.getTime())) throw new Error("Invalid date string");
    return date;
}

function formatLocalDate(dateString: string, options?: Intl.DateTimeFormatOptions): string {
    return stringToDate(dateString).toLocaleDateString(undefined, options ?? { year: "numeric", month: "long", day: "numeric" });
}

// Clock time only, no date — markers within a stream are all from the same day,
// so the date would just be noise beside them.
function formatLocalTime(dateString: string): string {
    return stringToDate(dateString).toLocaleTimeString(undefined, { hour: "2-digit", minute: "2-digit", second: "2-digit" });
}

function dateToTimeAgo(date: Date): string {
    const secondsAgo = Math.floor((Date.now() - date.getTime()) / 1000);
    const units: [number, string][] = [
        [31536000, 'year'],
        [2592000, 'month'],
        [86400, 'day'],
        [3600, 'hour'],
        [60, 'minute'],
    ];
    for (const [threshold, unit] of units) {
        if (secondsAgo >= threshold) {
            const count = Math.floor(secondsAgo / threshold);
            return `${count} ${unit}${count !== 1 ? 's' : ''} ago`;
        }
    }
    const s = Math.max(0, secondsAgo);
    return `${s} second${s !== 1 ? 's' : ''} ago`;
}

export { formatTime, stringToDate, dateToTimeAgo, formatLocalDate, formatLocalTime };
