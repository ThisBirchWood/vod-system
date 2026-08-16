import { Upload, Scissors, SlidersHorizontal, Radio, LayoutGrid, Play } from "lucide-react";

const features = [
    {
        icon: Upload,
        title: "Upload & compress",
        description: "Drop in a video file, set your clip range and export settings, then compress.",
    },
    {
        icon: Scissors,
        title: "Trim",
        description: "Set start and end points with a range slider. Preview as you go.",
    },
    {
        icon: SlidersHorizontal,
        title: "Export settings",
        description: "Choose resolution, frame rate, and an optional file size cap.",
    },
    {
        icon: Radio,
        title: "RTMP streaming",
        description: "Point OBS at your stream key and go live. Find the key in your profile.",
    },
    {
        icon: LayoutGrid,
        title: "Clip library",
        description: "Browse all your clips. Watch, rename, edit the description, or delete.",
    },
    {
        icon: Play,
        title: "Built-in player",
        description: "Scrub, adjust volume, go fullscreen. Runs in the browser.",
    },
];

const Home = () => {
    return (
        <div className="flex flex-col items-center px-8 py-12 min-h-full">
            {/* Hero */}
            <div className="flex flex-col items-center text-center max-w-2xl mb-14">
                <img
                    src="/logo.png"
                    alt="VoD System Logo"
                    className="h-24 mb-6 object-contain"
                />
                <h1 className="text-4xl font-heading text-text-primary">
                    A home for your clips.
                </h1>
                <p className="text-text-secondary">
                    Upload, trim, and export video clips. Stream live if you need to.
                </p>
            </div>

            {/* Feature grid */}
            <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-3 gap-4 w-full max-w-4xl mb-10">
                {features.map(({ icon: Icon, title, description }) => (
                    <div
                        key={title}
                        className="bg-card border border-hairline rounded-xl p-5 flex flex-col gap-3 shadow-sm"
                    >
                        <div className="w-9 h-9 rounded-lg flex items-center justify-center shrink-0">
                            <Icon size={18} className="text-terracotta" />
                        </div>
                        <div>
                            <p className="font-body text-text-strong font-bold text-sm mb-1">{title}</p>
                            <p className="text-sm text-text-secondary leading-relaxed">{description}</p>
                        </div>
                    </div>
                ))}
            </div>

            {/* Format note */}
            <p className="text-xs text-muted">
                Supports MP4, MOV, WebM, and OGG.
            </p>
        </div>
    );
};

export default Home;
