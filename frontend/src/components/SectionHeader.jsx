import { Link } from 'react-router-dom'
import arrowRight from '../assets/home/arrow-right-small.svg'

export default function SectionHeader({ id, title, badge, linkTo, linkLabel, children }) {
  return (
    <div className="section-header">
      <div className="section-header__title">
        <h2 id={id}>{title}</h2>
        {badge && <span className="section-header__badge">{badge}</span>}
      </div>
      <div className="section-header__actions">
        {linkTo && (
          <Link className="section-header__link" to={linkTo}>
            {linkLabel}
            <img src={arrowRight} alt="" width="14" height="14" />
          </Link>
        )}
        {children}
      </div>
    </div>
  )
}
