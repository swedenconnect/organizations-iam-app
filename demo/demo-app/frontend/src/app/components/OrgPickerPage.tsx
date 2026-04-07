import { CurrentUser, OrgEntry } from '@/types';
import { Button } from '@/app/components/ui/button';
import { Badge } from '@/app/components/ui/badge';
import diggLogo from '@/assets/DIGG.png';
import { LogOut } from 'lucide-react';

interface Props {
  orgs: OrgEntry[];
  onSelect: (orgId: string) => void;
  currentUser: CurrentUser;
}

export function OrgPickerPage({ orgs, onSelect, currentUser }: Props) {
  return (
    <div className="min-h-screen flex flex-col bg-background">
      <div className="flex justify-between items-center px-6 py-4 border-b border-gray-200 bg-white">
        <img src={diggLogo} alt="DIGG" className="h-8 object-contain" />
        <Button
          variant="outline"
          size="sm"
          onClick={() => { sessionStorage.removeItem('selectedOrgId'); window.location.href = '/logout'; }}
        >
          <LogOut className="h-4 w-4" />
          Log out
        </Button>
      </div>

      <div className="flex-1 flex flex-col items-center justify-center p-8">
        <div className="w-full max-w-md flex flex-col gap-6">
          <div className="text-center">
            <h1 className="text-2xl font-medium">Select Organization</h1>
            <p className="text-sm text-muted-foreground mt-1">
              You have access to multiple organizations. Select which one to work with.
            </p>
          </div>

          <div className="flex flex-col gap-3">
            {orgs.map((org) => (
              <button
                key={org.orgId}
                onClick={() => onSelect(org.orgId)}
                className="text-left rounded-lg border border-border bg-card px-4 py-3 hover:bg-muted/40 transition-colors cursor-pointer"
              >
                <div className="flex items-center justify-between gap-3">
                  <div>
                    <p className="font-medium">{org.orgName}</p>
                    <p className="text-sm text-muted-foreground">{org.orgId}</p>
                  </div>
                  <Badge variant="secondary">
                    {org.right.charAt(0).toUpperCase() + org.right.slice(1)}
                  </Badge>
                </div>
              </button>
            ))}
          </div>
        </div>
      </div>
    </div>
  );
}
