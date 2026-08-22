// layout/MainLayout.jsx
import Sidebar from '../components/Sidebar'
import Topbar from '../components/Topbar'
import { Outlet } from 'react-router-dom';
import {useEffect, useState} from "react";
import { useAuth } from "../auth/useAuth";
import { getStreamStatus } from "../utils/api/stream";

const MainLayout = () => {
    const [sidebarToggled, setSidebarToggled] = useState(false);
    const [isStreaming, setIsStreaming] = useState(false);
    const { user } = useAuth();

    useEffect(() => {
        if (!user) return;
        const poll = () => getStreamStatus().then(setIsStreaming).catch(() => {});
        poll();
        const interval = setInterval(poll, 10000);
        return () => clearInterval(interval);
    }, [user]);

    return (
        <div className={`transition-all duration-300 grid h-screen ${sidebarToggled ? "grid-cols-[0px_1fr]" : "grid-cols-[252px_1fr]"} grid-rows-[auto_1fr]`}>
            <Sidebar
                className={`row-span-2 transition-all duration-300 overflow-hidden whitespace-nowrap ${sidebarToggled ? "-translate-x-full" : "translate-x-0"}`}/>
            <Topbar
                className={"transition-all duration-300"}
                sidebarToggled={sidebarToggled}
                setSidebarToggled={setSidebarToggled}
                isStreaming={isStreaming}/>
            <div className="overflow-auto bg-background">
                <Outlet />
            </div>
        </div>
    );
};
export default MainLayout;
