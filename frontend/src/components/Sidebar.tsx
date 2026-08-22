import clsx from "clsx";
import SidebarButton from "./buttons/SidebarButton.tsx";
import { Plus, Film, Home, User as UserIcon, Video, Radio } from 'lucide-react';
import { useAuth } from "../auth/useAuth.ts";

type props = {
    className?: string
}

const Divider = () => <hr className="w-full my-1.5 mx-2 border-hover" />;

const Sidebar = ({className}: props) => {
    const { user } = useAuth();

    return (
        <aside className={clsx("h-screen flex flex-col bg-sidebar border-r border-hairline p-2 items-center gap-1", className)}>
            <h2 className={"font-heading text-2xl mt-1"}>The VoD System</h2>

            <nav className="flex flex-col flex-1 items-center w-3/4">
                <Divider />

                <div className="flex flex-col gap-0.5 w-full">
                    <SidebarButton url="/" logo={<Home size={18}/>} label="Home" />
                    <SidebarButton url="/create" logo={<Plus size={18}/>} label="Create Clip" />
                </div>

                <Divider />

                {user && (
                        <div className="flex flex-col gap-0.5 w-full">
                            <div>
                                <SidebarButton url="/stream" logo={<Radio size={18}/>} label="Stream" />
                                <SidebarButton url="/my-clips" logo={<Film size={18}/>} label="Clips" />
                                <SidebarButton url="/my-vods" logo={<Video size={18}/>} label="VoDs" />
                            </div>
                        </div>
                )}

                <div className="flex-1" />

                <div className={"w-full"}>
                    {user && (
                        <>
                            <Divider />
                            <SidebarButton url="/profile" logo={<UserIcon size={18}/>} label="Profile" />
                        </>
                    )}
                </div>
            </nav>
        </aside>
    );
};

export default Sidebar;
