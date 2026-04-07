document.addEventListener('DOMContentLoaded', function () {
  fetch('/api/theme')
    .then(function (r) { return r.json(); })
    .then(function (data) {
      var logo = document.getElementById('app-logo');
      if (logo) logo.src = data.logoUrl;

      var footerLogo = document.getElementById('app-footer-logo');
      if (footerLogo) footerLogo.src = data.footerLogoUrl;

      var favicon = document.querySelector('link[rel="icon"]');
      if (favicon) favicon.href = data.faviconUrl;

      document.documentElement.dataset.theme = data.mode;
    })
    .catch(function (err) {
      console.warn('Theme init failed:', err);
    });
});
