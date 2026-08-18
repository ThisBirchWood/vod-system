import clsx from "clsx";
import { formatTime, stringToDate, dateToTimeAgo } from "../../utils/utils.ts";
import { Link } from "react-router-dom";
import React, { useEffect, useRef, useState } from "react";
import { MoreVertical, Pencil, Trash2 } from "lucide-react";
import {config} from "../../config.ts";

type VideoCardProps = {
    id: number,
    title: string,
    duration: number,
    createdAt: string,
    onEdit?: () => void,
    onDelete?: () => void,
    className?: string,
    mediaApiPath?: string,
    playerPath?: string,
    itemLabel?: string,
}

const fallbackThumbnail = "public/default_thumbnail.png";
const API_URL = config.apiUrl;

const VideoCard = ({ id, title, duration, createdAt, onEdit, onDelete, className, mediaApiPath = '/api/v1/clips', playerPath = '/video', itemLabel = 'clip' }: VideoCardProps) => {
    const [timeAgo, setTimeAgo] = useState(dateToTimeAgo(stringToDate(createdAt)));
    const [imgFailed, setImgFailed] = useState(false);
    const [menuOpen, setMenuOpen] = useState(false);
    const [confirmDelete, setConfirmDelete] = useState(false);
    const menuRef = useRef<HTMLDivElement>(null);
    const thumbnailUrl = `${API_URL}${mediaApiPath}/${id}/thumbnail`;

    useEffect(() => {
        const interval = setInterval(() => {
            setTimeAgo(dateToTimeAgo(stringToDate(createdAt)));
        }, 1000);
        return () => clearInterval(interval);
    }, [createdAt]);

    useEffect(() => {
        if (!menuOpen) return;
        const handleClickOutside = (e: MouseEvent) => {
            if (menuRef.current && !menuRef.current.contains(e.target as Node)) {
                setMenuOpen(false);
                setConfirmDelete(false);
            }
        };
        document.addEventListener("mousedown", handleClickOutside);
        return () => document.removeEventListener("mousedown", handleClickOutside);
    }, [menuOpen]);

    const stopAndRun = (e: React.MouseEvent, fn: () => void) => {
        e.preventDefault();
        e.stopPropagation();
        fn();
    };

    return (
        <Link to={`${playerPath}/${id}`}>
            <div className={clsx("flex flex-col group cursor-pointer", className)}>
                <div className="relative overflow-hidden rounded-lg">
                    <img
                        src={imgFailed ? fallbackThumbnail : thumbnailUrl}
                        onError={() => setImgFailed(true)}
                        alt="Video Thumbnail"
                        className="w-full aspect-video object-cover group-hover:scale-105 transition-transform duration-200"
                    />

                    <span className="absolute top-1.5 left-1.5 bg-text-primary/75 text-on-dark px-1.5 py-0.5 rounded text-xs font-data pointer-events-none">
                        {formatTime(duration)}
                    </span>

                    {(onEdit || onDelete) && (
                        <div
                            ref={menuRef}
                            className="absolute top-1.5 right-1.5"
                            onClick={(e) => e.preventDefault()}
                        >
                            <button
                                onClick={(e) => stopAndRun(e, () => { setMenuOpen(v => !v); setConfirmDelete(false); })}
                                className="bg-text-primary/75 hover:bg-text-primary/90 text-on-dark rounded p-0.5 transition-colors"
                            >
                                <MoreVertical size={14} />
                            </button>

                            {menuOpen && (
                                <div className="absolute right-0 top-7 w-32 bg-card rounded-lg border border-hairline shadow-md z-50 py-1 overflow-hidden">
                                    {!confirmDelete && (<>
                                        {onEdit && (
                                            <button
                                                onClick={(e) => stopAndRun(e, onEdit)}
                                                className="flex items-center gap-2 w-full px-3 py-2 text-sm text-text-body hover:bg-hover"
                                            >
                                                <Pencil size={13} /> Edit
                                            </button>
                                        )}
                                        {onDelete && (
                                            <button
                                                onClick={(e) => stopAndRun(e, () => setConfirmDelete(true))}
                                                className="flex items-center gap-2 w-full px-3 py-2 text-sm text-error hover:bg-hover"
                                            >
                                                <Trash2 size={13} /> Delete
                                            </button>
                                        )}
                                    </>)}
                                    {confirmDelete && (
                                        <div className="px-3 py-2">
                                            <p className="text-xs text-text-secondary mb-2">Delete this {itemLabel}?</p>
                                            <div className="flex gap-1.5">
                                                <button
                                                    onClick={(e) => stopAndRun(e, onDelete!)}
                                                    className="flex-1 text-xs bg-error text-on-accent rounded px-2 py-1 hover:bg-terracotta-hover transition-colors"
                                                >
                                                    Delete
                                                </button>
                                                <button
                                                    onClick={(e) => stopAndRun(e, () => setConfirmDelete(false))}
                                                    className="flex-1 text-xs bg-fields text-text-secondary rounded px-2 py-1 hover:bg-hover transition-colors"
                                                >
                                                    Cancel
                                                </button>
                                            </div>
                                        </div>
                                    )}
                                </div>
                            )}
                        </div>
                    )}
                </div>

                <div className="flex flex-col p-2">
                    <p className="font-body text-text-strong text-sm leading-snug line-clamp-2">
                        {title === "" ? "(No Title)" : title}
                    </p>
                    <p className="text-muted text-xs mt-0.5">{timeAgo}</p>
                </div>
            </div>
        </Link>
    );
}

export default VideoCard;
