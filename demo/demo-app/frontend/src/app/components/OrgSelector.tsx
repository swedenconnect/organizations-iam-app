import { OrgEntry } from '@/types';
import { Label } from '@/app/components/ui/label';
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from '@/app/components/ui/select';

interface OrgSelectorProps {
  orgs: OrgEntry[];
  selected: string;
  onChange: (orgId: string) => void;
}

export function OrgSelector({ orgs, selected, onChange }: OrgSelectorProps) {
  return (
    <div className="flex flex-col gap-1.5">
      <Label htmlFor="org-select">Organisation</Label>
      <Select value={selected} onValueChange={onChange}>
        <SelectTrigger id="org-select" className="w-full max-w-xs">
          <SelectValue placeholder="Välj organisation" />
        </SelectTrigger>
        <SelectContent>
          {orgs.map((org) => (
            <SelectItem key={org.orgId} value={org.orgId}>
              {org.orgId}
            </SelectItem>
          ))}
        </SelectContent>
      </Select>
    </div>
  );
}
