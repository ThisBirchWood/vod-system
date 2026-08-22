import { useState } from "react";
import { Eye, EyeOff, Copy, Check } from "lucide-react";
import Box from "../components/Box.tsx";
import { useAuth } from "../auth/useAuth.ts";
import { formatLocalDate } from "../utils/utils.ts";

const Profile = () => {
    const { user } = useAuth();
    const [keyVisible, setKeyVisible] = useState(false);
    const [copied, setCopied] = useState(false);

    const handleCopy = () => {
        if (!user?.streamKey) return;
        navigator.clipboard.writeText(user.streamKey);
        setCopied(true);
        setTimeout(() => setCopied(false), 2000);
    };

    if (!user) {
        return (
            <div className="flex justify-center items-center h-full text-muted text-base">
                Please log in to view your profile.
            </div>
        );
    }

    return (
        <div className="px-8 py-10 max-w-2xl mx-auto">
            <h1 className="text-4xl font-heading text-text-primary mb-6">Profile</h1>

            <Box className="p-6 mb-4 flex items-center gap-5">
                <img
                    src={user.profilePictureUrl}
                    referrerPolicy="no-referrer"
                    alt="Profile picture"
                    className="w-20 h-20 rounded-full shadow-sm"
                />
                <div>
                    <p className="text-2xl font-heading text-text-primary">{user.name}</p>
                    <p className="text-sm text-text-secondary">@{user.username}</p>
                    <p className="text-sm text-text-secondary">{user.email}</p>
                    <p className="text-xs text-muted mt-1">
                        Member since {formatLocalDate(user.createdAt)}
                    </p>
                </div>
            </Box>

            <Box className="p-6">
                <h2 className="text-xl font-heading text-text-primary mb-1">Stream Key</h2>
                <p className="text-sm text-text-secondary mb-4">
                    Use this key in OBS or any RTMP-compatible software to start streaming.
                    Keep it private — anyone with this key can stream to your account.
                </p>

                <div className="flex items-center gap-2">
                    <div className="flex-1 bg-fields border border-hairline rounded-md px-4 py-2 font-data text-sm text-text-strong overflow-hidden">
                        {keyVisible ? user.streamKey : "•".repeat(user.streamKey.length)}
                    </div>

                    <button
                        onClick={() => setKeyVisible(v => !v)}
                        title={keyVisible ? "Hide key" : "Show key"}
                        className="p-2 rounded-md bg-fields hover:bg-hover transition-colors duration-150 text-text-secondary"
                    >
                        {keyVisible ? <EyeOff size={16} /> : <Eye size={16} />}
                    </button>

                    <button
                        onClick={handleCopy}
                        title="Copy key"
                        className="p-2 rounded-md bg-fields hover:bg-hover transition-colors duration-150 text-text-secondary"
                    >
                        {copied ? <Check size={16} className="text-olive" /> : <Copy size={16} />}
                    </button>
                </div>
            </Box>
        </div>
    );
};

export default Profile;
