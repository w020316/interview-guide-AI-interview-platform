/* =========================================================================
   offerGo — main.js
   Nav shrink, theme toggle (+localStorage), mobile menu, scroll progress,
   trust-bar count-up, and the "read-to-the-end" text lighting motif.
   Plain script (no modules / no imports) so it runs from file://.
   ========================================================================= */
(function () {
  "use strict";

  var root = document.documentElement;
  var reduceMotion =
    window.matchMedia &&
    window.matchMedia("(prefers-reduced-motion: reduce)").matches;

  /* ---------------- Theme ---------------- */
  var THEME_KEY = "offergo-theme";
  var themeToggle = document.querySelector("[data-theme-toggle]");

  function applyTheme(theme) {
    root.setAttribute("data-theme", theme);
    var isDark = theme === "dark";
    if (themeToggle) {
      themeToggle.setAttribute("aria-pressed", String(isDark));
      themeToggle.setAttribute(
        "aria-label",
        isDark ? "切换到浅色模式" : "切换到深色模式"
      );
    }
  }

  function storedTheme() {
    try {
      return window.localStorage.getItem(THEME_KEY);
    } catch (e) {
      return null;
    }
  }

  function storeTheme(theme) {
    try {
      window.localStorage.setItem(THEME_KEY, theme);
    } catch (e) {
      /* storage unavailable (private mode / file://) — ignore */
    }
  }

  (function initTheme() {
    var saved = storedTheme();
    if (saved === "dark" || saved === "light") {
      applyTheme(saved);
    } else {
      var prefersDark =
        window.matchMedia &&
        window.matchMedia("(prefers-color-scheme: dark)").matches;
      applyTheme(prefersDark ? "dark" : "light");
    }
  })();

  if (themeToggle) {
    themeToggle.addEventListener("click", function () {
      var next =
        root.getAttribute("data-theme") === "dark" ? "light" : "dark";
      applyTheme(next);
      storeTheme(next);
    });
  }

  /* ---------------- Nav shrink + mobile menu ---------------- */
  var nav = document.querySelector("[data-nav]");
  var navToggle = document.querySelector("[data-nav-toggle]");
  var navLinks = document.querySelector("[data-nav-links]");

  function closeMenu() {
    if (!navLinks) return;
    navLinks.classList.remove("is-open");
    if (navToggle) navToggle.setAttribute("aria-expanded", "false");
  }

  if (navToggle && navLinks) {
    navToggle.addEventListener("click", function () {
      var open = navLinks.classList.toggle("is-open");
      navToggle.setAttribute("aria-expanded", String(open));
    });
    // Close after choosing an anchor (mobile).
    navLinks.addEventListener("click", function (e) {
      if (e.target && e.target.closest("a")) closeMenu();
    });
    document.addEventListener("keydown", function (e) {
      if (e.key === "Escape") closeMenu();
    });
  }

  /* ---------------- Scroll progress bar ---------------- */
  var progressBar = document.querySelector("[data-progress]");

  /* ---------------- Unified scroll handler ---------------- */
  var ticking = false;
  function onScroll() {
    var doc = document.documentElement;
    var y = window.pageYOffset || doc.scrollTop;

    if (nav) {
      if (y > 24) nav.classList.add("is-shrunk");
      else nav.classList.remove("is-shrunk");
    }

    if (progressBar) {
      var max = doc.scrollHeight - window.innerHeight;
      var ratio = max > 0 ? Math.min(1, Math.max(0, y / max)) : 0;
      progressBar.style.transform = "scaleX(" + ratio + ")";
    }
    ticking = false;
  }

  function requestScroll() {
    if (ticking) return;
    ticking = true;
    window.requestAnimationFrame(onScroll);
  }

  window.addEventListener("scroll", requestScroll, { passive: true });
  window.addEventListener("resize", requestScroll, { passive: true });
  requestScroll();

  /* ---------------- Trust-bar count-up ---------------- */
  var counters = Array.prototype.slice.call(
    document.querySelectorAll("[data-count-to]")
  );

  function runCounter(el) {
    var target = parseFloat(el.getAttribute("data-count-to"));
    var decimals = parseInt(el.getAttribute("data-count-decimals") || "0", 10);
    var prefix = el.getAttribute("data-count-prefix") || "";
    var suffix = el.getAttribute("data-count-suffix") || "";
    if (isNaN(target)) return;

    if (reduceMotion) {
      el.textContent = prefix + target.toFixed(decimals) + suffix;
      return;
    }

    var duration = 1400;
    var start = null;
    function frame(ts) {
      if (start === null) start = ts;
      var p = Math.min(1, (ts - start) / duration);
      // easeOutExpo-ish for a settle feel
      var eased = p === 1 ? 1 : 1 - Math.pow(2, -10 * p);
      var value = target * eased;
      el.textContent = prefix + value.toFixed(decimals) + suffix;
      if (p < 1) window.requestAnimationFrame(frame);
      else el.textContent = prefix + target.toFixed(decimals) + suffix;
    }
    window.requestAnimationFrame(frame);
  }

  function makeCountObserver() {
    if (!("IntersectionObserver" in window)) {
      counters.forEach(runCounter);
      return;
    }
    var co = new IntersectionObserver(
      function (entries) {
        entries.forEach(function (entry) {
          if (entry.isIntersecting) {
            runCounter(entry.target);
            co.unobserve(entry.target);
          }
        });
      },
      { threshold: 0.4 }
    );
    counters.forEach(function (el) {
      co.observe(el);
    });

    // Backup: instant jumps skip the callback entirely.
    function sweep() {
      counters.forEach(function (el) {
        if (el.getAttribute("data-counted") === "1") return;
        var rect = el.getBoundingClientRect();
        if (rect.top < window.innerHeight * 0.9 || rect.bottom < 0) {
          el.setAttribute("data-counted", "1");
          runCounter(el);
          co.unobserve(el);
        }
      });
    }
    window.addEventListener("scroll", function () {
      window.requestAnimationFrame(sweep);
    }, { passive: true });
    window.requestAnimationFrame(sweep);
  }

  counters.forEach(function (el) {
    el.setAttribute("data-counted", "0");
    // The HTML ships the REAL value so the no-JS fallback tells the truth.
    // Here we reset to zero so the count-up can animate from 0.
    var dec = parseInt(el.getAttribute("data-count-decimals") || "0", 10);
    var pre = el.getAttribute("data-count-prefix") || "";
    var suf = el.getAttribute("data-count-suffix") || "";
    el.textContent = pre + (0).toFixed(dec) + suf;
  });
  makeCountObserver();

  /* ---------------- Read-to-the-end lighting ---------------- */
  var readers = Array.prototype.slice.call(
    document.querySelectorAll("[data-reader]")
  );

  function lightReader(reader) {
    var lines = reader.querySelectorAll(".reader__line");
    for (var i = 0; i < lines.length; i++) {
      lines[i].style.setProperty("--i", String(i));
      lines[i].classList.add("is-lit");
    }
    reader.classList.add("is-lit");
  }

  function initReader(reader) {
    if (reduceMotion || !("IntersectionObserver" in window)) {
      lightReader(reader);
      return;
    }
    // Hold the "unread" state for a short beat before lighting, so the visual
    // metaphor (half-read resume) is perceivable — but keep it snappy: the
    // reader line is the LCP element, so a long hold delays LCP.
    var LIGHT_DELAY = 420;
    var timer = null;
    function scheduleLight(delay) {
      if (timer) window.clearTimeout(timer);
      timer = window.setTimeout(function () { lightReader(reader); }, delay);
    }
    var ro = new IntersectionObserver(
      function (entries) {
        entries.forEach(function (entry) {
          if (entry.isIntersecting) {
            scheduleLight(LIGHT_DELAY);
            ro.unobserve(entry.target);
          }
        });
      },
      { threshold: 0.35 }
    );
    ro.observe(reader);
    reader.addEventListener("mouseenter", function () {
      if (timer) window.clearTimeout(timer);
      lightReader(reader);
      ro.unobserve(reader);
    });
    reader.addEventListener("focusin", function () {
      if (timer) window.clearTimeout(timer);
      lightReader(reader);
    });

    // Backup for instant jumps.
    function sweep() {
      if (reader.classList.contains("is-lit")) return;
      var rect = reader.getBoundingClientRect();
      if (rect.top < window.innerHeight * 0.85 || rect.bottom < 0) {
        scheduleLight(LIGHT_DELAY);
        ro.unobserve(reader);
      }
    }
    window.addEventListener("scroll", function () {
      window.requestAnimationFrame(sweep);
    }, { passive: true });
    window.requestAnimationFrame(sweep);
  }

  readers.forEach(initReader);

  /* ---------------- In-page anchor scroll (respect reduced motion) ------- */
  document.addEventListener("click", function (e) {
    var link = e.target && e.target.closest('a[href^="#"]');
    if (!link) return;
    var id = link.getAttribute("href");
    if (id === "#" || id.length < 2) return;
    var target = document.querySelector(id);
    if (!target) return;
    e.preventDefault();
    target.scrollIntoView({
      behavior: reduceMotion ? "auto" : "smooth",
      block: "start"
    });
    // Move keyboard focus to the target for a11y.
    target.setAttribute("tabindex", "-1");
    target.focus({ preventScroll: true });
  });
})();
