import React from "react";

type Props = {
    children: React.ReactNode;
    label: string;
};

const Selector = ({ children, label }: Props) => (
    <div className="flex items-center gap-2">
        <label className="w-full text-sm text-text-secondary">{label}</label>
        <div className="w-px h-5 text-text-secondary mx-2" />
        {children}
    </div>
);

export default Selector;
