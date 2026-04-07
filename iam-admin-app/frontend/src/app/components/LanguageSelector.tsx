import { useLanguage } from '@/app/contexts/LanguageContext';
import { Button } from '@/app/components/ui/button';
import { Globe } from 'lucide-react';

interface LanguageSelectorProps {
  className?: string;
}

export function LanguageSelector({ className }: LanguageSelectorProps) {
  const { language, setLanguage } = useLanguage();

  const toggleLanguage = () => {
    setLanguage(language === 'en' ? 'sv' : 'en');
  };

  const displayLanguage = language === 'en' ? 'Svenska' : 'English';

  return (
    <Button
      variant="ghost"
      size="sm"
      className={`gap-2 ${className ?? ''}`}
      onClick={toggleLanguage}
    >
      <Globe className="w-4 h-4" />
      {displayLanguage}
    </Button>
  );
}
