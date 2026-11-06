export default function StarIcon({ size = 12 }) {
  return (
    <svg
      className="star-icon"
      width={size}
      height={size}
      viewBox="0 0 12 12"
      aria-hidden="true"
      focusable="false"
    >
      <path
        d="M6 .9l1.52 3.1 3.42.5-2.47 2.4.58 3.4L6 8.7 2.95 10.3l.58-3.4L1.06 4.5l3.42-.5z"
        fill="currentColor"
      />
    </svg>
  )
}
