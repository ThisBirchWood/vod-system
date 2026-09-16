import type { ElementType } from "react";

type Props = {
    icon: ElementType;
    title: string;
    accent?: "primary" | "accent";
};

const CardHeader = ({ icon: Icon, title, accent = "primary" }: Props) => (
    <div className="flex items-center gap-2.5">
        <div className={`w-8 h-8 rounded-lg flex items-center justify-center flex-shrink-0 ${accent === "primary" ? "bg-terracotta/10" : "bg-olive/10"}`}>
            <Icon size={16} className={accent === "primary" ? "text-terracotta" : "text-olive"} />
        </div>
        <h2 className="text-xl font-heading text-text-primary">{title}</h2>
    </div>
);

export default CardHeader;
