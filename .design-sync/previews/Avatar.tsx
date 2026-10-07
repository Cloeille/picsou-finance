import {
  Avatar,
  AvatarFallback,
  AvatarBadge,
  AvatarGroup,
  AvatarGroupCount,
} from "picsou"
import { Check } from "lucide-react"

export function Sizes() {
  return (
    <div className="flex items-center gap-3">
      <Avatar size="sm">
        <AvatarFallback>CM</AvatarFallback>
      </Avatar>
      <Avatar size="default">
        <AvatarFallback>AL</AvatarFallback>
      </Avatar>
      <Avatar size="lg">
        <AvatarFallback>ZP</AvatarFallback>
      </Avatar>
    </div>
  )
}

export function MemberColors() {
  return (
    <div className="flex items-center gap-3">
      <Avatar size="lg" className="rounded-lg">
        <AvatarFallback
          style={{ backgroundColor: "#0055ff" }}
          className="font-bold text-white"
        >
          CM
        </AvatarFallback>
      </Avatar>
      <Avatar size="lg" className="rounded-lg">
        <AvatarFallback
          style={{ backgroundColor: "#e8590c" }}
          className="font-bold text-white"
        >
          AL
        </AvatarFallback>
      </Avatar>
      <Avatar size="lg" className="rounded-lg">
        <AvatarFallback className="bg-muted font-bold text-muted-foreground">
          ZP
        </AvatarFallback>
      </Avatar>
    </div>
  )
}

export function WithBadge() {
  return (
    <div className="flex items-center gap-4">
      <Avatar size="lg">
        <AvatarFallback style={{ backgroundColor: "#0055ff" }} className="text-white">
          CM
        </AvatarFallback>
        <AvatarBadge>
          <Check />
        </AvatarBadge>
      </Avatar>
      <Avatar size="default">
        <AvatarFallback>AL</AvatarFallback>
        <AvatarBadge className="bg-emerald-500" />
      </Avatar>
    </div>
  )
}

export function FamilyGroup() {
  return (
    <AvatarGroup>
      <Avatar>
        <AvatarFallback style={{ backgroundColor: "#0055ff" }} className="text-white">
          CM
        </AvatarFallback>
      </Avatar>
      <Avatar>
        <AvatarFallback style={{ backgroundColor: "#e8590c" }} className="text-white">
          AL
        </AvatarFallback>
      </Avatar>
      <Avatar>
        <AvatarFallback style={{ backgroundColor: "#12b886" }} className="text-white">
          ZP
        </AvatarFallback>
      </Avatar>
      <AvatarGroupCount>+2</AvatarGroupCount>
    </AvatarGroup>
  )
}
