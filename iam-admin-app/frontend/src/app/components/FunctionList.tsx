import { FunctionType, Organization, OrganizationFunction } from '@/types';
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
import { Pencil, Trash2, Search, Boxes } from 'lucide-react';
import { useLanguage } from '@/app/contexts/LanguageContext';
import { useState } from 'react';

interface FunctionListProps {
  functions: FunctionType[];
  organizations: Organization[];
  organizationFunctions: OrganizationFunction[];
  isSuperuser: boolean;
  allowFunctionRemoval: boolean;
  onEdit: (func: FunctionType) => void;
  onDelete: (id: string) => void;
  onNavigateToOrg: (orgId: string) => void;
}

export function FunctionList({
  functions,
  organizations,
  organizationFunctions,
  isSuperuser,
  allowFunctionRemoval,
  onEdit,
  onDelete,
  onNavigateToOrg,
}: FunctionListProps) {
  const { t, language } = useLanguage();
  const [searchTerm, setSearchTerm] = useState('');
  const [confirmDeleteFunc, setConfirmDeleteFunc] = useState<FunctionType | null>(null);

  const getOrgName = (org: Organization) => {
    return language === 'sv' ? org.nameSv : org.nameEn;
  };

  const getDisplayName = (func: FunctionType): string =>
    (language === 'sv' ? func.nameSv : func.nameEn) ||
    (language === 'sv' ? func.nameEn : func.nameSv) ||
    func.name;

  const getDescription = (func: FunctionType): string =>
    (language === 'sv' ? func.descriptionSv : func.descriptionEn) ||
    (language === 'sv' ? func.descriptionEn : func.descriptionSv) ||
    '';

  const filteredFunctions = functions.filter((func) => {
    const searchLower = searchTerm.toLowerCase();
    return (
      func.name.toLowerCase().includes(searchLower) ||
      func.nameSv.toLowerCase().includes(searchLower) ||
      func.nameEn.toLowerCase().includes(searchLower) ||
      func.descriptionSv.toLowerCase().includes(searchLower) ||
      func.descriptionEn.toLowerCase().includes(searchLower)
    );
  });

  const getAssignedOrganizations = (functionId: string) => {
    return organizationFunctions
      .filter((of) => of.functionId === functionId)
      .map((of) => organizations.find((o) => o.id === of.organizationId))
      .filter((org) => org !== undefined) as Organization[];
  };

  return (
    <div className="space-y-4">
      {/* Search */}
      <div className="relative">
        <Search className="absolute left-3 top-1/2 transform -translate-y-1/2 w-4 h-4 text-gray-400" />
        <Input
          type="text"
          placeholder={t('search.functions') || 'Search functions by name or description'}
          value={searchTerm}
          onChange={(e) => setSearchTerm(e.target.value)}
          className="pl-10"
        />
      </div>

      {filteredFunctions.length === 0 ? (
        <Card>
          <CardContent className="pt-6">
            <div className="text-center text-gray-500">
              <Boxes className="w-12 h-12 mx-auto mb-2 opacity-20" />
              <p>{searchTerm ? t('common.noResults') : t('functions.empty')}</p>
            </div>
          </CardContent>
        </Card>
      ) : (
        filteredFunctions.map((func) => {
          const assignedOrgs = getAssignedOrganizations(func.id);
          return (
            <Card key={func.id}>
              <CardHeader>
                <div className="flex items-start justify-between">
                  <div className="flex-1">
                    <CardTitle className="flex items-center gap-2">
                      <Boxes className="w-5 h-5" />
                      {getDisplayName(func)}
                    </CardTitle>
                    <div className="mt-1 space-y-1">
                      <p className="text-sm text-gray-500">
                        {t('functions.name')}: <code className="bg-gray-100 px-1 rounded">{func.name}</code>
                      </p>
                      {getDescription(func) && <p className="text-sm text-gray-600">{getDescription(func)}</p>}
                    </div>
                  </div>
                  {isSuperuser && (
                    <div className="flex gap-2">
                      <Button
                        variant="outline"
                        size="sm"
                        onClick={() => onEdit(func)}
                      >
                        <Pencil className="w-4 h-4" />
                      </Button>
                      {allowFunctionRemoval && (
                        <Button
                          variant="outline"
                          size="sm"
                          onClick={() => setConfirmDeleteFunc(func)}
                        >
                          <Trash2 className="w-4 h-4 text-red-500" />
                        </Button>
                      )}
                    </div>
                  )}
                </div>
              </CardHeader>
              {assignedOrgs.length > 0 && (
                <CardContent>
                  <div className="space-y-2">
                    <p className="text-sm font-medium text-gray-600">{t('functions.assignedOrgs')}:</p>
                    <div className="flex flex-wrap gap-2">
                      {assignedOrgs.map((org) => (
                        <button
                          key={org.id}
                          onClick={() => onNavigateToOrg(org.id)}
                          className="inline-flex items-center px-2.5 py-0.5 rounded-full text-xs font-medium bg-gray-100 text-gray-800 hover:bg-primary/10 hover:text-primary cursor-pointer transition-colors"
                        >
                          {getOrgName(org)}
                        </button>
                      ))}
                    </div>
                  </div>
                </CardContent>
              )}
            </Card>
          );
        })
      )}

      {/* Delete function confirmation */}
      <AlertDialog open={confirmDeleteFunc !== null} onOpenChange={() => setConfirmDeleteFunc(null)}>
        <AlertDialogContent>
          <AlertDialogHeader>
            <AlertDialogTitle>{t('confirm.deleteFuncTitle')}</AlertDialogTitle>
            <AlertDialogDescription>
              {t('confirm.deleteFuncDescriptionPrefix')}
              {confirmDeleteFunc
                ? (language === 'sv' ? confirmDeleteFunc.nameSv : confirmDeleteFunc.nameEn) ||
                  confirmDeleteFunc.name
                : ''}
              {t('confirm.deleteFuncDescriptionSuffix')}
            </AlertDialogDescription>
          </AlertDialogHeader>
          <AlertDialogFooter>
            <AlertDialogCancel onClick={() => setConfirmDeleteFunc(null)}>
              {t('common.cancel')}
            </AlertDialogCancel>
            <AlertDialogAction
              className="bg-red-600 hover:bg-red-700 text-white"
              onClick={() => {
                if (confirmDeleteFunc) {
                  onDelete(confirmDeleteFunc.id);
                  setConfirmDeleteFunc(null);
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