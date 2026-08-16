import { Menu, X } from 'lucide-react';
import { login, logout } from "../utils/api/users.ts";
import { Dropdown, DropdownItem } from "./Dropdown.tsx";
import { GoogleLogin } from '@react-oauth/google';

import type { User } from "../utils/types.ts";
import type { CredentialResponse } from '@react-oauth/google';

import MenuButton from "./buttons/MenuButton.tsx";
import clsx from "clsx";
import {useNavigate} from "react-router-dom";


type props = {
    sidebarToggled: boolean;
    setSidebarToggled: (toggled: boolean) => void;
    user: User | null;
    fetchUser: () => void;
    isStreaming: boolean;
    className?: string;
}

const Topbar = ({
                    sidebarToggled,
                    setSidebarToggled,
                    user,
                    fetchUser,
                    isStreaming,
                    className}: props) => {

    const navigate = useNavigate();

    const handleLogin = (response: CredentialResponse) => {
        if (!response.credential) {
            console.error("No credential received from Google login.");
            return;
        }

        login(response.credential)
            .then(() => {
                fetchUser();
                navigate(0);
            })
            .catch((error) => {
                console.error("Login failed:", error);
            });
    }

    const handleLogout = () => {
        logout()
            .then(() => {
                fetchUser();
                navigate("/");
            })
            .catch((error) => {
                console.error("Logout failed:", error);
            });
    }

    return (
        <div className={clsx(className, "flex justify-between items-center px-4 py-2 bg-sidebar border-b border-hairline")}>
            <MenuButton onClick={() => setSidebarToggled(!sidebarToggled)}>
                {sidebarToggled ? <Menu size={24}/> :  <X size={24}/>}
            </MenuButton>

            { user ? (
                <div className={"hover:bg-hover rounded-lg p-0.5"}>
                    <img
                        className={"w-8 h-8 rounded-full inline-block"}
                        src={user.profilePictureUrl}
                        referrerPolicy="no-referrer"
                    />

                    <Dropdown label={
                        <span className="flex items-center gap-1.5">
                            {user.name}
                            {isStreaming && (
                                <span className="relative flex h-2 w-2">
                                    <span className="animate-ping absolute inline-flex h-full w-full rounded-full bg-live opacity-75" />
                                    <span className="relative inline-flex rounded-full h-2 w-2 bg-live" />
                                </span>
                            )}
                        </span>
                    }>
                        <DropdownItem item="Profile"
                                      onClick={() => navigate("/profile")} />
                        <DropdownItem item="Logout"
                                      onClick={() => handleLogout()}
                                      className={"text-error font-medium"} />
                    </Dropdown>
                </div>
            ) :
            (
                <GoogleLogin
                    shape={"pill"}
                    useOneTap={false}
                    onSuccess={(credentialResponse) => handleLogin(credentialResponse)} />
            )}
        </div>
    )
}

export default Topbar;