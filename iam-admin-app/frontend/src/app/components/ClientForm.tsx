import { useState, useEffect } from 'react';
import { FunctionType, ManagedClient, ManagedClientInput } from '@/types';
import { Button } from '@/app/components/ui/button';
import { Input } from '@/app/components/ui/input';
import { Label } from '@/app/components/ui/label';
import { Textarea } from '@/app/components/ui/textarea';
import { Dialog, DialogContent, DialogHeader, DialogTitle, DialogFooter } from '@/app/components/ui/dialog';
import { Plus, X } from 'lucide-react';
import { useLanguage } from '@/app/contexts/LanguageContext';

interface ClientFormProps {
  client: ManagedClient | null;
  functions: FunctionType[];
  isOpen: boolean;
  onClose: () => void;
  onSave: (client: ManagedClientInput) => void;
}

type JwksMode = 'uri' | 'inline';

export function ClientForm({ client, functions, isOpen, onClose, onSave }: ClientFormProps) {
  const { t, language } = useLanguage();
  const [clientId, setClientId] = useState('');
  const [name, setName] = useState('');
  const [oidcClient, setOidcClient] = useState(true);
  const [resourceServer, setResourceServer] = useState(false);
  const [redirectUris, setRedirectUris] = useState<string[]>(['']);
  const [selectedFunctions, setSelectedFunctions] = useState<string[]>([]);
  const [jwksMode, setJwksMode] = useState<JwksMode>('uri');
  const [jwksUri, setJwksUri] = useState('');
  const [jwksString, setJwksString] = useState('');
  const [orgRightsIdToken, setOrgRightsIdToken] = useState(true);
  const [orgRightsAccessToken, setOrgRightsAccessToken] = useState(true);
  const [fieldErrors, setFieldErrors] = useState<Record<string, string>>({});

  useEffect(() => {
    if (client) {
      setClientId(client.clientId);
      setName(client.name ?? '');
      setOidcClient(client.oidcClient);
      setResourceServer(client.resourceServer);
      setRedirectUris(client.redirectUris.length > 0 ? client.redirectUris : ['']);
      setSelectedFunctions(client.functions);
      setJwksMode(client.jwksString ? 'inline' : 'uri');
      setJwksUri(client.jwksUri ?? '');
      setJwksString(client.jwksString ?? '');
      setOrgRightsIdToken(client.orgRightsIdToken);
      setOrgRightsAccessToken(client.orgRightsAccessToken);
    } else {
      setClientId('');
      setName('');
      setOidcClient(true);
      setResourceServer(false);
      setRedirectUris(['']);
      setSelectedFunctions([]);
      setJwksMode('uri');
      setJwksUri('');
      setJwksString('');
      setOrgRightsIdToken(true);
      setOrgRightsAccessToken(true);
    }
    setFieldErrors({});
  }, [client, isOpen]);

  const getFunctionLabel = (func: FunctionType): string =>
    (language === 'sv' ? func.nameSv : func.nameEn) || func.name;

  // A client marked as handling all functions is registered by script, and the server maintains
  // its function list as functions are created. There is nothing here to pick, and whatever the
  // form sent would be ignored.
  const allFunctions = client?.allFunctions ?? false;

  const toggleFunction = (functionId: string) => {
    setSelectedFunctions((current) =>
      current.includes(functionId)
        ? current.filter((f) => f !== functionId)
        : [...current, functionId]
    );
  };

  const setRedirectUri = (index: number, value: string) => {
    setRedirectUris((current) => current.map((uri, i) => (i === index ? value : uri)));
  };

  // Keycloak treats a redirect URI as a wildcard pattern only when the `*` is the last character
  // and the pattern carries no query string. Anywhere else the `*` is matched literally, giving a
  // client whose callbacks silently never match, so those forms are refused.
  const wildcardOnlyAtEnd = (value: string): boolean => {
    const first = value.indexOf('*');
    if (first < 0) return true;
    return first === value.length - 1 && !value.includes('?');
  };

  const removeRedirectUri = (index: number) => {
    setRedirectUris((current) =>
      current.length === 1 ? [''] : current.filter((_, i) => i !== index)
    );
  };

  const isAbsoluteUri = (value: string): boolean => {
    try {
      return Boolean(new URL(value).protocol);
    } catch {
      return false;
    }
  };

  const handleSubmit = (e: React.FormEvent) => {
    e.preventDefault();
    const errors: Record<string, string> = {};
    const uris = redirectUris.map((uri) => uri.trim()).filter((uri) => uri.length > 0);

    if (!client && !clientId.trim()) {
      errors.clientId = t('validation.required');
    }
    if (!oidcClient && !resourceServer) {
      errors.roles = t('clients.validation.roleRequired');
    }
    if (oidcClient) {
      if (uris.length === 0) {
        errors.redirectUris = t('clients.validation.redirectRequired');
      } else if (!uris.every(wildcardOnlyAtEnd)) {
        errors.redirectUris = t('clients.validation.wildcardPosition');
      } else if (uris.some((uri) => !isAbsoluteUri(uri))) {
        errors.redirectUris = t('clients.validation.absoluteUri');
      }
      if (jwksMode === 'uri') {
        if (!jwksUri.trim()) {
          errors.jwks = t('validation.required');
        } else if (!jwksUri.trim().startsWith('https://')) {
          errors.jwks = t('clients.validation.jwksHttps');
        }
      } else if (!jwksString.trim()) {
        errors.jwks = t('validation.required');
      }
    }

    if (Object.keys(errors).length > 0) {
      setFieldErrors(errors);
      return;
    }
    setFieldErrors({});

    onSave({
      clientId: client ? client.clientId : clientId.trim(),
      name: name.trim(),
      oidcClient,
      resourceServer,
      functions: selectedFunctions,
      redirectUris: oidcClient ? uris : [],
      jwksUri: oidcClient && jwksMode === 'uri' ? jwksUri.trim() : null,
      jwksString: oidcClient && jwksMode === 'inline' ? jwksString.trim() : null,
      orgRightsIdToken: !oidcClient || orgRightsIdToken,
      orgRightsAccessToken: !oidcClient || orgRightsAccessToken,
    });
  };

  return (
    <Dialog open={isOpen} onOpenChange={onClose}>
      {/* Fixed height: the form's sections come and go with the selected roles, and a dialog that
          resizes under the cursor is worse than one with spare room at the bottom */}
      <DialogContent className="max-w-2xl h-[85vh] overflow-y-auto content-start">
        <DialogHeader>
          <DialogTitle>{client ? t('clients.edit') : t('clients.create')}</DialogTitle>
        </DialogHeader>
        <form onSubmit={handleSubmit} className="space-y-4">

          {/* Roles — a client may be either or both */}
          <div className="space-y-2">
            <Label>{t('clients.roles')} *</Label>
            <div className="border rounded-md divide-y">
              <label className="flex items-start gap-3 p-3 cursor-pointer">
                <input
                  type="checkbox"
                  className="mt-1"
                  checked={oidcClient}
                  onChange={(e) => setOidcClient(e.target.checked)}
                />
                <span>
                  <span className="text-sm font-medium">{t('clients.roleOidcClient')}</span>
                  <span className="block text-xs text-gray-500 mt-0.5">
                    {t('clients.roleOidcClientHint')}
                  </span>
                </span>
              </label>
              <label className="flex items-start gap-3 p-3 cursor-pointer">
                <input
                  type="checkbox"
                  className="mt-1"
                  checked={resourceServer}
                  onChange={(e) => setResourceServer(e.target.checked)}
                />
                <span>
                  <span className="text-sm font-medium">{t('clients.roleResourceServer')}</span>
                  <span className="block text-xs text-gray-500 mt-0.5">
                    {t('clients.roleResourceServerHint')}
                  </span>
                </span>
              </label>
            </div>
            {fieldErrors.roles && <p className="text-xs text-red-500">{fieldErrors.roles}</p>}
          </div>

          {/* Client ID — immutable after creation */}
          <div className="space-y-2">
            <Label htmlFor="clientId">{t('clients.clientId')} *</Label>
            <Input
              id="clientId"
              value={clientId}
              onChange={(e) => setClientId(e.target.value)}
              disabled={Boolean(client)}
            />
            <p className="text-xs text-gray-500">
              {client ? t('clients.clientIdImmutable') : t('clients.clientIdHint')}
            </p>
            {/* Reserved line: shown only for a resource server, but always occupying space so
                toggling the role does not shift the fields below */}
            <p className="text-xs text-gray-500 min-h-4">
              {resourceServer ? t('services.clientIdHint') : '\u00A0'}
            </p>
            {fieldErrors.clientId && <p className="text-xs text-red-500">{fieldErrors.clientId}</p>}
          </div>

          {/* Display name — OIDC client only */}
          {oidcClient && (
            <div className="space-y-2">
              <Label htmlFor="clientName">{t('clients.name')}</Label>
              <Input id="clientName" value={name} onChange={(e) => setName(e.target.value)} />
            </div>
          )}

          {/* Redirect URIs — OIDC client only */}
          {oidcClient && (
          <div className="space-y-2">
            <Label>{t('clients.redirectUris')} *</Label>
            {redirectUris.map((uri, index) => (
              <div key={index} className="flex gap-2 items-center">
                <Input
                  value={uri}
                  onChange={(e) => setRedirectUri(index, e.target.value)}
                  aria-label={`${t('clients.redirectUris')} ${index + 1}`}
                />
                <Button
                  type="button"
                  variant="outline"
                  size="icon"
                  onClick={() => removeRedirectUri(index)}
                  aria-label={t('common.remove')}
                >
                  <X className="w-4 h-4" />
                </Button>
              </div>
            ))}
            <Button
              type="button"
              variant="ghost"
              size="sm"
              className="text-primary px-0"
              onClick={() => setRedirectUris((current) => [...current, ''])}
            >
              <Plus className="w-4 h-4 mr-1" />
              {t('clients.addRedirectUri')}
            </Button>
            <p className="text-xs text-gray-500">{t('clients.redirectUrisHint')}</p>
            {fieldErrors.redirectUris && (
              <p className="text-xs text-red-500">{fieldErrors.redirectUris}</p>
            )}
          </div>
          )}

          {/* Functions. A client marked as handling all functions has its list maintained by the
              server as functions are created, so there is nothing here to pick */}
          <div className="space-y-2">
            <Label>{t('clients.functions')}</Label>
            <div className="flex flex-wrap gap-2">
              {functions.map((func) => {
                const selected = allFunctions || selectedFunctions.includes(func.id);
                return (
                  <button
                    type="button"
                    key={func.id}
                    onClick={() => toggleFunction(func.id)}
                    aria-pressed={selected}
                    disabled={allFunctions}
                    className={`px-3 py-1.5 rounded-full border text-sm transition-colors ${
                      selected
                        ? 'bg-primary text-primary-foreground border-primary'
                        : 'bg-white border-gray-200 hover:bg-gray-50'
                    } ${allFunctions ? 'opacity-60 cursor-not-allowed' : ''}`}
                  >
                    {getFunctionLabel(func)}
                  </button>
                );
              })}
            </div>
            <p className="text-xs text-gray-500">
              {allFunctions ? t('clients.allFunctionsHint') : t('clients.functionsHint')}
            </p>
          </div>

          {/* Client keys — OIDC client only */}
          {oidcClient && (
          <div className="space-y-2">
            <Label>{t('clients.jwks')} *</Label>
            <div className="flex gap-4">
              <label className="flex items-center gap-2 text-sm">
                <input
                  type="radio"
                  name="jwksMode"
                  checked={jwksMode === 'uri'}
                  onChange={() => setJwksMode('uri')}
                />
                {t('clients.jwksUri')}
              </label>
              <label className="flex items-center gap-2 text-sm">
                <input
                  type="radio"
                  name="jwksMode"
                  checked={jwksMode === 'inline'}
                  onChange={() => setJwksMode('inline')}
                />
                {t('clients.jwksInline')}
              </label>
            </div>
            {jwksMode === 'uri' ? (
              <Input
                value={jwksUri}
                onChange={(e) => setJwksUri(e.target.value)}
                aria-label={t('clients.jwksUri')}
              />
            ) : (
              <Textarea
                value={jwksString}
                onChange={(e) => setJwksString(e.target.value)}
                rows={8}
                className="font-mono text-xs"
                aria-label={t('clients.jwksInline')}
              />
            )}
            <p className="text-xs text-gray-500">{t('clients.jwksHint')}</p>
            {fieldErrors.jwks && <p className="text-xs text-red-500">{fieldErrors.jwks}</p>}
          </div>
          )}

          {/* Token settings — OIDC client only */}
          {oidcClient && (
          <div className="space-y-2">
            <Label>{t('clients.tokenSettings')}</Label>
            <div className="border rounded-md divide-y">
              <label className="flex items-start gap-3 p-3 cursor-pointer">
                <input
                  type="checkbox"
                  className="mt-1"
                  checked={orgRightsIdToken}
                  onChange={(e) => setOrgRightsIdToken(e.target.checked)}
                />
                <span>
                  <span className="text-sm font-medium">{t('clients.orgRightsIdToken')}</span>
                  <span className="block text-xs text-gray-500 mt-0.5">
                    {t('clients.orgRightsIdTokenHint')}
                  </span>
                </span>
              </label>
              <label className="flex items-start gap-3 p-3 cursor-pointer">
                <input
                  type="checkbox"
                  className="mt-1"
                  checked={orgRightsAccessToken}
                  onChange={(e) => setOrgRightsAccessToken(e.target.checked)}
                />
                <span>
                  <span className="text-sm font-medium">{t('clients.orgRightsAccessToken')}</span>
                  <span className="block text-xs text-gray-500 mt-0.5">
                    {t('clients.orgRightsAccessTokenHint')}
                  </span>
                </span>
              </label>
            </div>
            {client?.serviceAccount && (
              <p className="text-xs text-gray-500">{t('clients.serviceAccountHint')}</p>
            )}
          </div>
          )}

          <DialogFooter>
            <Button type="button" variant="outline" onClick={onClose}>
              {t('common.cancel')}
            </Button>
            <Button type="submit" className="bg-primary hover:bg-primary/90">
              {client ? t('common.save') : t('clients.create')}
            </Button>
          </DialogFooter>
        </form>
      </DialogContent>
    </Dialog>
  );
}
