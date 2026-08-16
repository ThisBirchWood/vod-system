// layout/MainLayout.jsx
import Sidebar from '../components/Sidebar'
import Topbar from '../components/Topbar'
import { Outlet } from 'react-router-dom';
import {useEffect, useState} from "react";
import type {User} from "../utils/types";
import { getUser } from "../utils/api/users";
import { getStreamStatus } from "../utils/api/stream";

const MainLayout = () => {
    const [sidebarToggled, setSidebarToggled] = useState(false);
    const [user, setUser] = useState<null | User>(null);
    const [isStreaming, setIsStreaming] = useState(false);

    const fetchUser = async () => {
        try {
            const userData = await getUser();
            setUser(userData);
        } catch (error) {
            console.error('Failed to fetch user:', error);
        }
    };

    useEffect(() => {
        fetchUser();
    }, []);

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
                user={user}
                className={`row-span-2 transition-all duration-300 overflow-hidden whitespace-nowrap ${sidebarToggled ? "-translate-x-full" : "translate-x-0"}`}/>
            <Topbar
                className={"transition-all duration-300"}
                sidebarToggled={sidebarToggled}
                setSidebarToggled={setSidebarToggled}
                user={user}
                fetchUser={fetchUser}
                isStreaming={isStreaming}/>
            <div className="overflow-auto bg-background">
                <Outlet />
            </div>
        </div>
    );
};
export default MainLayout;