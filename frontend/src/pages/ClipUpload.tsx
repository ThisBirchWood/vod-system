import {useState} from "react";
import {useNavigate} from "react-router-dom";
import Box from "../components/Box.tsx";

const ClipUpload = () => {
    const [file, setFile] = useState<File | null>(null);
    const [error, setError] = useState<null | string>(null);
    const navigate = useNavigate();

    const press = () => {
        if (!file) {
            setError("Please choose a file");
            return;
        }
        navigate('/create/new', { state: { file } });
    };

    return (
        <div className="flex justify-center p-8">
            <Box className="flex flex-col gap-4 p-6 w-full max-w-md">
                <div
                    className="cursor-pointer rounded-lg border-2
                                border-dashed border-hairline bg-dropzone
                                text-center text-sm text-text-secondary
                                hover:bg-hover transition-colors duration-150
                                min-h-50 flex flex-col items-center justify-center gap-2"
                    >
                    <label className="cursor-pointer flex flex-col items-center justify-center gap-2 w-full h-full">
                        Choose file
                        <input
                            type="file"
                            onChange={(e) => {
                                const selected = e.target.files?.[0] ?? null;
                                setFile(selected);
                                setError(null);
                            }}
                            className="hidden"
                        />
                    </label>
                    {file && (
                        <p className="mt-2 text-xs text-text-secondary">{file.name}</p>
                    )}
                </div>

                <button
                    onClick={press}
                    className={"hover:bg-hover p-1 rounded-md"}
                >
                    Upload
                </button>

                {error && <p className="text-sm font-body text-center">{error}</p>}
            </Box>
        </div>
    )
};

export default ClipUpload;
