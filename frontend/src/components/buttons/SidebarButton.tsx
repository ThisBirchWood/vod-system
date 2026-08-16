import { Link, useLocation } from "react-router-dom";
import React from "react";

type Props = {
    url: string;
    logo: React.ReactNode;
    label: string;
}

const SidebarButton = ({url, logo, label}: Props) => {
    const { pathname } = useLocation();
    const isActive = url === "/" ? pathname === "/" : pathname.startsWith(url);

    return (
        <Link className="w-full" to={url}>
            <button className={`flex items-center gap-2.5 w-full text-sm 
            font-body p-2 hover:bg-hover hover:text-text-strong
            rounded-md 
            ${isActive ?
                "bg-card text-text-strong font-medium shadow-sm"
                :
                "text-text-primary"}`}>
                {logo}{label}
            </button>
        </Link>
    )
}

export default SidebarButton;