import clsx from "clsx";
import React from "react";

type Props = {
    children: React.ReactNode;
    className?: string;
};

const Box = ({ children, className }: Props) => (
    <div className={clsx("bg-card border-hairline shadow-sm rounded-lg", className)}>
        {children}
    </div>
);

export default Box;
