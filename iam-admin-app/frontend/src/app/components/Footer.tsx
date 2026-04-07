import { useState, useEffect } from 'react';
import { useLanguage } from '@/app/contexts/LanguageContext';
import { useTheme } from '@/app/contexts/ThemeContext';

interface ThemeFooterLink {
  label: string;
  url: string;
}

interface ThemeFooter {
  orgName: string;
  contactEmail: string;
  contactPhone: string;
  links: ThemeFooterLink[];
}

export function Footer() {
  const { t, language } = useLanguage();
  const { footerLogoUrl, footerLogoHeight } = useTheme();
  const [footerData, setFooterData] = useState<ThemeFooter | null>(null);

  useEffect(() => {
    fetch(`/api/theme/footer?lang=${language}`)
      .then((r) => r.json())
      .then(setFooterData)
      .catch(() => {});
  }, [language]);

  return (
    <footer className="mt-auto">
      <div className="text-white text-center py-3 text-sm" style={{ backgroundColor: 'var(--footer-tagline-bg)' }}>
        {t('footer.tagline')}
      </div>
      <div className="text-white py-6" style={{ backgroundColor: 'var(--footer-bg)' }}>
        <div className="max-w-7xl mx-auto px-4 sm:px-6 lg:px-8">
          <div className="flex items-center justify-between">
            <div className="flex items-center gap-2">
              <img
                src={footerLogoUrl}
                alt="Logotype"
                className={`${footerLogoHeight} object-contain`}
                onError={(e) => { (e.target as HTMLImageElement).style.display = 'none'; }}
              />
            </div>
            {footerData && (
              <nav className="flex items-center gap-6 mx-auto">
                {footerData.links.map((link, i) => (
                  <span key={link.url} className="flex items-center gap-6">
                    {i > 0 && <span className="text-gray-400">|</span>}
                    <a href={link.url} className="text-sm hover:underline">{link.label}</a>
                  </span>
                ))}
              </nav>
            )}
            <div className="w-8"></div>
          </div>
        </div>
      </div>
    </footer>
  );
}
