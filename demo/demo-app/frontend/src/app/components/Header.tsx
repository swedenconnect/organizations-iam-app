import { Button } from '@/app/components/ui/button';
import { CurrentUser, OrgEntry } from '@/types';
import diggLogo from '@/assets/DIGG.png';
import { LogOut } from 'lucide-react';

interface HeaderProps {
  currentUser?: CurrentUser | null;
  selectedOrg?: OrgEntry | null;
}

export function Header({ currentUser, selectedOrg }: HeaderProps) {
  return (
    <header className="border-b border-gray-200 bg-white">
      <div className="max-w-7xl mx-auto px-4 sm:px-6 lg:px-8 py-4">
        <div className="flex items-center justify-between">
          <div className="flex items-center gap-3">
            <img src={diggLogo} alt="DIGG" className="h-8 object-contain" />
          </div>
          <div className="flex items-center gap-3">
            {currentUser && (
              <div className="flex flex-col items-end">
                <span className="text-sm">{currentUser.name}</span>
                {selectedOrg && (
                  <span className="text-xs text-muted-foreground">
                    Access: {selectedOrg.right}
                  </span>
                )}
                {selectedOrg && (
                  <span className="text-xs text-muted-foreground">{selectedOrg.orgName}</span>
                )}
              </div>
            )}
            {currentUser && (
              <Button
                variant="outline"
                size="sm"
                onClick={() => { sessionStorage.removeItem('selectedOrgId'); window.location.href = '/logout'; }}
              >
                <LogOut className="h-4 w-4" />
                Log out
              </Button>
            )}
          </div>
        </div>
      </div>
    </header>
  );
}
