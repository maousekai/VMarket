import { useState } from 'react'
import { Link } from 'react-router-dom'
import arrowRight from '../assets/home/arrow-right.svg'
import chevronLeft from '../assets/home/chevron-left.svg'

export default function HeroCarousel({ slides }) {
  const [index, setIndex] = useState(0)
  if (!slides?.length) return null
  const count = slides.length
  const slide = slides[index]
  const go = (next) => setIndex((next + count) % count)

  function onKeyDown(event) {
    if (event.key === 'ArrowLeft') go(index - 1)
    else if (event.key === 'ArrowRight') go(index + 1)
    else return
    event.preventDefault()
  }

  return (
    <section
      className="hero-carousel"
      aria-roledescription="carousel"
      aria-label="Nổi bật hôm nay"
      onKeyDown={onKeyDown}
    >
      <div
        className="hero-carousel__slide"
        key={slide.id}
        role="group"
        aria-roledescription="slide"
        aria-label={`${index + 1} / ${count}`}
      >
        <h2 className="hero-carousel__title">
          {slide.title.map((line) => (
            <span key={line}>{line}</span>
          ))}
        </h2>
        <p className="hero-carousel__description">{slide.description}</p>
        <Link className="hero-carousel__cta" to={slide.to}>
          {slide.cta}
          <img src={arrowRight} alt="" width="16" height="16" />
        </Link>
      </div>
      {count > 1 && (
        <>
          <p className="hero-carousel__counter" aria-hidden="true">
            {index + 1} / {count}
          </p>
          <button
            className="hero-carousel__arrow hero-carousel__arrow--previous"
            type="button"
            aria-label="Banner trước"
            onClick={() => go(index - 1)}
          >
            <img src={chevronLeft} alt="" width="16" height="16" />
          </button>
          <button
            className="hero-carousel__arrow hero-carousel__arrow--next"
            type="button"
            aria-label="Banner tiếp theo"
            onClick={() => go(index + 1)}
          >
            <img src={chevronLeft} alt="" width="16" height="16" />
          </button>
          <div className="hero-carousel__dots">
            {slides.map((item, dot) => (
              <button
                key={item.id}
                type="button"
                className="hero-carousel__dot"
                aria-label={`Chuyển tới banner ${dot + 1}`}
                aria-current={dot === index ? 'true' : undefined}
                onClick={() => go(dot)}
              />
            ))}
          </div>
        </>
      )}
    </section>
  )
}
