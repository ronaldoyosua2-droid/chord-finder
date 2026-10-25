(function () {
  var CHORD = /^[A-G][#b]?(maj|min|dim|aug|sus|add|no|omit|m|M|[0-9]|#|b|\+|-|°|ø|\(|\)|,|\^)*(\/([A-G][#b]?|[0-9]+))?$/;
  var SYMBOL = /^([|\/\\\-.:•%~*_=>()\[\]{}]+|\(?x?\d{1,2}x?\)?)$/i;
  function isChordLine(line) {
    var toks = line.trim().split(/\s+/);
    if (!toks[0]) return false;
    var chords = 0;
    for (var i = 0; i < toks.length; i++) {
      var t = toks[i];
      if (SYMBOL.test(t)) continue;
      var core = t.replace(/^[(\[{|]+/, '').replace(/[)\]}|,.:;]+$/, '');
      if (CHORD.test(core)) { chords++; continue; }
      if (chords === 0 && (/:$/.test(t) || /^\[.*\]$/.test(t) ||
          /^(intro|verse|bait|reff?|chorus|bridge|interlude|int|outro|ending|coda|musik|instrumen|inst)$/i.test(core))) continue;
      return false;
    }
    return chords > 0;
  }
  function score(text) {
    var lines = text.split('\n'), n = 0;
    for (var i = 0; i < lines.length; i++) if (isChordLine(lines[i])) n++;
    return n;
  }
  var nodes = document.querySelectorAll('pre, code, textarea, article, section, main, td, div, p');
  var best = null, bestScore = 0, bestLen = 1e9;
  for (var i = 0; i < nodes.length; i++) {
    var el = nodes[i];
    var txt = (el.tagName === 'TEXTAREA') ? el.value : el.innerText;
    if (!txt || txt.length > 40000) continue;
    var s = score(txt);
    if (s === 0) continue;
    // pilih skor tertinggi; jika hampir sama, pilih elemen yang lebih ringkas
    if (s > bestScore * 1.05 || (s >= bestScore * 0.95 && txt.length < bestLen)) {
      best = txt; bestScore = Math.max(s, bestScore); bestLen = txt.length;
    }
  }
  // Situs chord (JRChord, PsalmNote) biasanya menaruh chord di <pre>; utamakan itu
  var pres = document.querySelectorAll('pre');
  var preBest = null, preScore = 0;
  for (var j = 0; j < pres.length; j++) {
    var pt = pres[j].innerText || '';
    var ps = score(pt);
    if (ps > preScore) { preScore = ps; preBest = pt; }
  }
  if (preBest && preScore >= 3 && preScore >= bestScore * 0.6) { best = preBest; bestScore = preScore; }
  var h1 = document.querySelector('h1');
  var title = (h1 && h1.innerText.trim()) || document.title || '';
  return JSON.stringify({ title: title, pageTitle: document.title || '', text: best || '', score: bestScore, url: location.href });
})();
