import { Lock } from 'lucide-react';
import {useNavigate} from "react-router-dom";

const NotFound = () => {
    const navigate = useNavigate();

    const goHomePage = () => {
        navigate("/")
    }

    return (
        <div className="w-full min-h-screen flex flex-col items-center justify-center gap-3 text-center px-6">
            <Lock className="w-8 h-8 text-faint-mono/60 mb-1" strokeWidth={1.5} />
            <h2 className="font-heading text-2xl md:text-3xl text-faint-mono">
                This page does not exist.
            </h2>
            <p className="text-sm text-faint-mono/70 max-w-xs">
                <a onClick={goHomePage}>Click here</a> to get back to the home page.
            </p>
        </div>
    );
}

export default NotFound;