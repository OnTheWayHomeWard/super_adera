/* Adera docs: small progressive enhancements. Everything works without JS (just no copy buttons / filter). */
(function () {
  "use strict";
  var root = document.documentElement;

  // theme toggle (remembered per browser; storage may be unavailable on file://)
  try { var saved = localStorage.getItem("adera-docs-theme"); if (saved) root.setAttribute("data-theme", saved); } catch (e) {}
  var tbtn = document.getElementById("theme-btn");
  if (tbtn) tbtn.addEventListener("click", function () {
    var dark = root.getAttribute("data-theme") === "dark" ||
      (!root.getAttribute("data-theme") && window.matchMedia("(prefers-color-scheme: dark)").matches);
    var next = dark ? "light" : "dark";
    root.setAttribute("data-theme", next);
    try { localStorage.setItem("adera-docs-theme", next); } catch (e) {}
  });

  // mobile menu
  var mbtn = document.getElementById("menu-btn");
  if (mbtn) mbtn.addEventListener("click", function () { document.body.classList.toggle("menu-open"); });
  document.addEventListener("click", function (e) {
    if (document.body.classList.contains("menu-open") && !e.target.closest(".sidebar") && e.target !== mbtn) document.body.classList.remove("menu-open");
  });

  // copy buttons on <pre>
  document.querySelectorAll("pre").forEach(function (pre) {
    if (pre.classList.contains("nocopy")) return;
    var b = document.createElement("button");
    b.className = "copy"; b.type = "button"; b.textContent = "Copy";
    b.addEventListener("click", function () {
      var text = pre.innerText.replace(/\nCopy$/, "").replace(/^output\n/, "");
      var done = function () { b.textContent = "Copied"; setTimeout(function () { b.textContent = "Copy"; }, 1200); };
      if (navigator.clipboard && navigator.clipboard.writeText) navigator.clipboard.writeText(text).then(done, fallback);
      else fallback();
      function fallback() {
        var ta = document.createElement("textarea"); ta.value = text; document.body.appendChild(ta); ta.select();
        try { document.execCommand("copy"); done(); } catch (e) {} document.body.removeChild(ta);
      }
    });
    pre.appendChild(b);
  });

  // endpoint filter + expand/collapse (pages that contain details.endpoint)
  var eps = Array.prototype.slice.call(document.querySelectorAll("details.endpoint"));
  if (eps.length) {
    var bar = document.createElement("div"); bar.className = "toolbar";
    bar.innerHTML = '<input type="search" placeholder="Filter endpoints (method, path, words)..." aria-label="Filter endpoints">' +
      '<button type="button" data-a="open">Expand all</button><button type="button" data-a="close">Collapse all</button>';
    var first = eps[0]; first.parentNode.insertBefore(bar, first);
    var input = bar.querySelector("input");
    input.addEventListener("input", function () {
      var q = input.value.toLowerCase().trim();
      eps.forEach(function (d) { d.classList.toggle("hidden", q && d.textContent.toLowerCase().indexOf(q) === -1 && d.id.toLowerCase().indexOf(q) === -1); });
    });
    bar.addEventListener("click", function (e) {
      var a = e.target.getAttribute && e.target.getAttribute("data-a"); if (!a) return;
      eps.forEach(function (d) { if (!d.classList.contains("hidden")) d.open = a === "open"; });
    });
    // open the endpoint that the URL fragment points at
    var openHash = function () {
      var id = decodeURIComponent(location.hash.slice(1)); if (!id) return;
      var el = document.getElementById(id);
      if (el) { var d = el.closest("details.endpoint"); if (d) { d.open = true; el.scrollIntoView(); } }
    };
    window.addEventListener("hashchange", openHash); openHash();
  }
})();
