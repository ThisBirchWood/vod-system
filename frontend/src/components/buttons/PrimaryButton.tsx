import React from "react";

type Props = React.ButtonHTMLAttributes<HTMLButtonElement>;

const PrimaryButton: React.FC<Props> = ({ className = "", ...props }) => {
  return (
    <button
      className={`bg-terracotta text-on-accent font-body text-sm rounded-lg hover:bg-terracotta-hover disabled:bg-terracotta-disabled transition-colors duration-150 h-10 px-4 ${className}`}
      {...props}
    />
  );
};

export default PrimaryButton;