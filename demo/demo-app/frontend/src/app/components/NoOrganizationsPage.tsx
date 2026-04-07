import { Button } from '@/app/components/ui/button';
import diggLogo from '@/assets/DIGG.png';
import { LogOut } from 'lucide-react';

interface Props {
  userName: string;
}

export function NoOrganizationsPage({ userName }: Props) {
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
        <div className="w-full max-w-md flex flex-col gap-4 text-center">
          <h1 className="text-2xl font-medium">No Organization Access</h1>
          <p className="text-sm text-muted-foreground">
            You are logged in as <strong>{userName}</strong>, but you are not connected
            to any organization. Contact an administrator to have your account assigned
            to an organization.
          </p>
        </div>
      </div>
    </div>
  );
}
