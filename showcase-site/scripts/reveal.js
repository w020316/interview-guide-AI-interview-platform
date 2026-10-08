/* =========================================================================
   offerGo — reveal.js
   Scroll-triggered reveal. No dependencies, no modules (file:// safe).
   - Adds .js-ready to <html> so hidden styles only apply when JS runs.
   - Falls back to instant display when IntersectionObserver is missing.
   - Backs up the observer on scroll/resize: instant jumps (anchor links,
     scrollTo, End key) can move an element from below to above the viewport
     with ratio 0 and the observer never fires — we detect "already past"
     and reveal manually.
   ========================================================================= */
(function () {
  "use strict";

  var root = document.documentElement;
  var SELECTOR = "[data-reveal]";

  function revealAll() {
    var nodes = document.querySelectorAll(SELECTOR);
    for (var i = 0; i < nodes.length; i++) {
      nodes[i].classList.add("is-in");
    }
  }

  function prefersReduced() {
    return (
      window.matchMedia &&
      window.matchMedia("(prefers-reduced-motion: reduce)").matches
    );
  }

  // No IntersectionObserver OR reduced motion OR no JS-hidden support:
  // never leave content stuck at opacity:0.
  if (!("IntersectionObserver" in window) || prefersReduced()) {
    // Only add .js-ready when we can safely reveal everything immediately.
    root.classList.add("js-ready");
    revealAll();
    return;
  }

  root.classList.add("js-ready");

  var nodes = Array.prototype.slice.call(document.querySelectorAll(SELECTOR));

  function isPastViewport(el) {
    var rect = el.getBoundingClientRect();
    // Element has entered, or has been scrolled past the top.
    return rect.top < window.innerHeight * 0.92 || rect.bottom < 0;
  }

  var io = new IntersectionObserver(
    function (entries) {
      entries.forEach(function (entry) {
        if (entry.isIntersecting) {
          entry.target.classList.add("is-in");
          io.unobserve(entry.target);
        }
      });
    },
    { root: null, rootMargin: "0px 0px -8% 0px", threshold: 0.08 }
  );

  nodes.forEach(function (el) {
    io.observe(el);
  });

  // Backup sweep for instant jumps: any not-yet-revealed node that is now
  // inside or above the viewport gets revealed so it never stays hidden.
  var ticking = false;
  function sweep() {
    ticking = false;
    for (var i = 0; i < nodes.length; i++) {
      var el = nodes[i];
      if (el.classList.contains("is-in")) continue;
      if (isPastViewport(el)) {
        el.classList.add("is-in");
        io.unobserve(el);
      }
    }
  }
  function onScrollOrResize() {
    if (ticking) return;
    ticking = true;
    window.requestAnimationFrame(sweep);
  }

  window.addEventListener("scroll", onScrollOrResize, { passive: true });
  window.addEventListener("resize", onScrollOrResize, { passive: true });
  // Initial pass in case the page loads already scrolled.
  window.requestAnimationFrame(sweep);
})();
