import { Button } from '@/app/components/ui/button';
import { LogOut } from 'lucide-react';
import { LanguageSelector } from '@/app/components/LanguageSelector';
import { useLanguage } from '@/app/contexts/LanguageContext';
import { useTheme } from '@/app/contexts/ThemeContext';

interface CurrentUser {
  sub: string;
  name: string;
  email: string;
}

interface HeaderProps {
  onLogout?: () => void;
  showLogout?: boolean;
  currentUser?: CurrentUser | null;
}

export function Header({ onLogout, showLogout = false, currentUser }: HeaderProps) {
  const { t } = useLanguage();
  const { logoUrl, logoHeight } = useTheme();

  return (
    <header className="border-b border-gray-200" style={{ backgroundColor: 'var(--header-bg)' }}>
      <div className="max-w-7xl mx-auto px-4 sm:px-6 lg:px-8 py-4">
        <div className="flex items-center justify-between">
          <div className="flex items-center gap-3">
            <img src={logoUrl} alt="Logotype" className={`${logoHeight} object-contain`} />
          </div>
          <div className="flex items-center gap-2">
            {currentUser?.name && (
              <span className="text-sm" style={{ color: 'var(--header-fg)' }}>{currentUser.name}</span>
            )}
            <LanguageSelector className="text-[color:var(--header-fg)] hover:text-[color:var(--header-fg)] hover:bg-white/10" />
            {showLogout && onLogout && (
              <Button variant="outline" onClick={onLogout} size="sm">
                <LogOut className="w-4 h-4 mr-2" />
                {t('header.logout')}
              </Button>
            )}
          </div>
        </div>
      </div>
    </header>
  );
}
