/**
 * Temporary app mark. The final logo will replace this SVG and public/favicon.svg (same shape);
 * colors come from the accent tokens so it follows the theme.
 */
export function LogoMark({ size = 22 }: { size?: number }) {
  return (
    <svg className="logo-mark" width={size} height={size} viewBox="0 0 32 32" aria-hidden="true">
      <rect className="logo-mark__bg" width="32" height="32" rx="8" />
      <path
        className="logo-mark__glyph"
        d="M11 10l-5 6 5 6M21 10l5 6-5 6M18 8l-4 16"
        strokeWidth="2.5"
        fill="none"
        strokeLinecap="round"
        strokeLinejoin="round"
      />
    </svg>
  );
}
