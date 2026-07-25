 // App.jsx
import { BrowserRouter as Router, Routes, Route } from 'react-router-dom';
import MainLayout from './layouts/MainLayout';
import ClipUpload from './pages/ClipUpload';
import ClipEdit from './pages/ClipEdit';
import Home from './pages/Home';
import {useEffect} from "react";
import MyClips from './pages/MyClips';
import MyVods from './pages/MyVods';
import MediaPlayer from "./pages/MediaPlayer.tsx";
import { getVodById, getVodMediaUrl } from "./utils/api/vods.ts";
import { getClipById, getClipMediaUrl } from "./utils/api/clips.ts";
import EditClip from "./pages/EditClip.tsx";
import EditVod from "./pages/EditVod.tsx";
import Profile from "./pages/Profile.tsx";
import StreamControl from "./pages/StreamControl.tsx";
import { GoogleOAuthProvider } from '@react-oauth/google';


function App() {
    useEffect(() => {
        document.title = "VoD System";
    }, []);

    return (
        <GoogleOAuthProvider clientId={import.meta.env.VITE_GOOGLE_CLIENT_ID}>
            <Router>
                <Routes>
                    <Route element={<MainLayout />}>
                        <Route path="/" element={<Home />} />
                        <Route path="/create" element={<ClipUpload />} />
                        <Route path="/create/:id" element={<ClipEdit />} />
                        <Route path="/my-clips" element={<MyClips />} />
                        <Route path="/my-vods" element={<MyVods />} />
                        <Route path="/video/:id" element={<MediaPlayer noun="Clip" getMediaUrl={getClipMediaUrl} fetchDetails={getClipById} />} />
                        <Route path="/vod/:id" element={<MediaPlayer noun="VoD" getMediaUrl={getVodMediaUrl} fetchDetails={getVodById} />} />
                        <Route path="/clips/:id/edit" element={<EditClip />} />
                        <Route path="/vods/:id/edit" element={<EditVod />} />
                        <Route path="/profile" element={<Profile />} />
                        <Route path="/stream" element={<StreamControl />} />
                    </Route>
                </Routes>
            </Router>
        </ GoogleOAuthProvider>
    );
}

export default App;