import { useState } from 'react';
import { FunctionType, ManagedClient } from '@/types';
import { Button } from '@/app/components/ui/button';
import { Card, CardContent, CardHeader, CardTitle } from '@/app/components/ui/card';
import { Input } from '@/app/components/ui/input';
import {
  AlertDialog,
  AlertDialogAction,
  AlertDialogCancel,
  AlertDialogContent,
  AlertDialogDescription,
  AlertDialogFooter,
  AlertDialogHeader,
  AlertDialogTitle,
} from '@/app/components/ui/alert-dialog';
import { Pencil, Search, Trash2 } from 'lucide-react';
import { useLanguage } from '@/app/contexts/LanguageContext';

interface ClientListProps {
  clients: ManagedClient[];
  functions: FunctionType[];
  /** client_id -> artifacts a reconciliation would create. Absent or 0 means fully provisioned. */
  drift?: Record<string, number>;
  onEdit: (client: ManagedClient) => void;
  onDelete: (id: string) => void;
}

export function ClientList({
  clients,
  functions,
  drift,
  onEdit,
  onDelete,
}: ClientListProps) {
  const { t, language } = useLanguage();
  const [searchTerm, setSearchTerm] = useState('');
  const [confirmDeleteClient, setConfirmDeleteClient] = useState<ManagedClient | null>(null);

  const getFunctionLabel = (functionId: string): string => {
    const func = functions.find((f) => f.id === functionId);
    if (!func) return functionId;
    return (language === 'sv' ? func.nameSv : func.nameEn) || func.name;
  };

  const filteredClients = clients.filter((client) => {
    const searchLower = searchTerm.toLowerCase();
    return (
      client.clientId.toLowerCase().includes(searchLower) ||
      (client.name ?? '').toLowerCase().includes(searchLower) ||
      client.functions.some((f) => f.toLowerCase().includes(searchLower))
    );
  });

  if (clients.length === 0) {
    return <p className="text-sm text-gray-500">{t('services.empty')}</p>;
  }

  return (
    <div className="space-y-4">
      <div className="relative">
        <Search className="absolute left-3 top-1/2 -translate-y-1/2 w-4 h-4 text-gray-400" />
        <Input
          value={searchTerm}
          onChange={(e) => setSearchTerm(e.target.value)}
          placeholder={t('clients.search')}
          className="pl-9"
        />
      </div>

      {filteredClients.map((client) => (
        <Card key={client.clientId}>
          <CardHeader className="pb-3">
            <div className="flex items-start justify-between gap-4">
              <div className="min-w-0">
                <div className="flex items-center gap-2">
                  <CardTitle className="text-base truncate">
                    {client.name || client.clientId}
                  </CardTitle>
                  {client.oidcClient && (
                    <span className="px-2 py-0.5 rounded-full text-xs shrink-0 bg-secondary text-secondary-foreground">
                      {t('clients.badgeClient')}
                    </span>
                  )}
                  {client.resourceServer && (
                    <span className="px-2 py-0.5 rounded-full text-xs shrink-0 bg-muted text-muted-foreground">
                      {t('clients.badgeResourceServer')}
                    </span>
                  )}
                  {client.serviceAccount && (
                    <span
                      className="px-2 py-0.5 rounded-full text-xs shrink-0 border border-amber-300 bg-amber-50 text-amber-800"
                      title={t('clients.badgeServiceAccountHint')}
                    >
                      {t('clients.badgeServiceAccount')}
                    </span>
                  )}
                  {client.allFunctions && (
                    <span
                      className="px-2 py-0.5 rounded-full text-xs shrink-0 border border-sky-300 bg-sky-50 text-sky-800"
                      title={t('clients.badgeAllFunctionsHint')}
                    >
                      {t('clients.badgeAllFunctions')}
                    </span>
                  )}
                  {(drift?.[client.clientId] ?? 0) > 0 && (
                    <span
                      className="px-2 py-0.5 rounded-full text-xs shrink-0 border border-amber-400 bg-amber-100 text-amber-900"
                      title={t('clients.driftHint')}
                    >
                      {t('clients.driftBadge').replace(
                        '{missing}',
                        String(drift?.[client.clientId] ?? 0)
                      )}
                    </span>
                  )}
                </div>
                <p className="text-xs text-gray-500 mt-1 break-all">{client.clientId}</p>
              </div>
              {/* A client holding a service account is registered by script and carries the
                  Keycloak Admin API access — this application neither edits nor deletes it, and a
                  delete is refused server-side too. The title sits on the wrapper because a
                  disabled button takes no pointer events and would never show it */}
              <div
                className="flex gap-2 shrink-0"
                title={client.serviceAccount ? t('clients.serviceAccountUnmanaged') : undefined}
              >
                <Button
                  variant="outline"
                  size="icon"
                  onClick={() => onEdit(client)}
                  aria-label={t('clients.edit')}
                  disabled={client.serviceAccount}
                >
                  <Pencil className="w-4 h-4" />
                </Button>
                <Button
                  variant="outline"
                  size="icon"
                  className="text-red-600 hover:text-red-700"
                  onClick={() => setConfirmDeleteClient(client)}
                  aria-label={t('common.delete')}
                  disabled={client.serviceAccount}
                >
                  <Trash2 className="w-4 h-4" />
                </Button>
              </div>
            </div>
          </CardHeader>
          <CardContent className="space-y-3 text-sm">
            <div>
              <p className="text-xs font-medium text-gray-500 uppercase tracking-wide">
                {client.oidcClient ? t('clients.functions') : t('services.functions')}
              </p>
              {client.allFunctions ? (
                <p className="text-gray-600 mt-1">{t('clients.allFunctionsNote')}</p>
              ) : client.functions.length === 0 ? (
                <p className="text-amber-700 mt-1">{t('clients.unscoped')}</p>
              ) : (
                <div className="flex flex-wrap gap-2 mt-1">
                  {client.functions.map((functionId) => (
                    <span
                      key={functionId}
                      className="px-2.5 py-0.5 rounded-full bg-secondary text-secondary-foreground text-xs"
                    >
                      {getFunctionLabel(functionId)}
                    </span>
                  ))}
                </div>
              )}
            </div>

            {client.oidcClient ? (
              <>
                <div>
                  <p className="text-xs font-medium text-gray-500 uppercase tracking-wide">
                    {t('clients.redirectUris')}
                  </p>
                  <ul className="mt-1 space-y-0.5">
                    {client.redirectUris.map((uri) => (
                      <li key={uri} className="break-all text-gray-700">{uri}</li>
                    ))}
                  </ul>
                </div>

                <div>
                  <p className="text-xs font-medium text-gray-500 uppercase tracking-wide">
                    {t('clients.jwks')}
                  </p>
                  <p className="mt-1 break-all text-gray-700">
                    {client.jwksUri ?? t('clients.jwksInline')}
                  </p>
                </div>
              </>
            ) : null}
          </CardContent>
        </Card>
      ))}

      <AlertDialog
        open={confirmDeleteClient !== null}
        onOpenChange={() => setConfirmDeleteClient(null)}
      >
        <AlertDialogContent>
          <AlertDialogHeader>
            <AlertDialogTitle>{t('clients.confirmDeleteTitle')}</AlertDialogTitle>
            <AlertDialogDescription>
              {t('clients.confirmDeletePrefix')}
              {confirmDeleteClient?.clientId ?? ''}
              {t('clients.confirmDeleteSuffix')}
            </AlertDialogDescription>
          </AlertDialogHeader>
          <AlertDialogFooter>
            <AlertDialogCancel onClick={() => setConfirmDeleteClient(null)}>
              {t('common.cancel')}
            </AlertDialogCancel>
            <AlertDialogAction
              className="bg-red-600 hover:bg-red-700 text-white"
              onClick={() => {
                if (confirmDeleteClient) {
                  onDelete(confirmDeleteClient.id);
                  setConfirmDeleteClient(null);
                }
              }}
            >
              {t('common.confirm')}
            </AlertDialogAction>
          </AlertDialogFooter>
        </AlertDialogContent>
      </AlertDialog>
    </div>
  );
}
