import { Body, Controller, Delete, Get, HttpCode, Param, Patch } from '@nestjs/common';
import { ApiBearerAuth, ApiOperation, ApiProperty, ApiTags } from '@nestjs/swagger';
import { Device } from '@prisma/client';
import { Transform } from 'class-transformer';
import { IsNotEmpty, IsString, MaxLength } from 'class-validator';
import { CurrentPrincipal, Principal } from '../../common/decorators/current-principal.decorator';
import { DevicesService } from './devices.service';

class RenameDeviceDto {
  @ApiProperty({ example: 'Living Room TV' })
  @Transform(({ value }: { value: unknown }) => (typeof value === 'string' ? value.trim() : value))
  @IsString()
  @IsNotEmpty({ message: 'Enter a name' })
  @MaxLength(60, { message: 'Name is too long' })
  name!: string;
}

export interface DeviceView {
  id: string;
  type: string;
  name: string;
  manufacturer: string | null;
  model: string | null;
  osVersion: string | null;
  appVersion: string | null;
  createdAt: string;
  lastSeenAt: string | null;
  current: boolean;
}

export function toDeviceView(device: Device, currentDeviceId?: string): DeviceView {
  return {
    id: device.id,
    type: device.type,
    name: device.name,
    manufacturer: device.manufacturer,
    model: device.model,
    osVersion: device.osVersion,
    appVersion: device.appVersion,
    createdAt: device.createdAt.toISOString(),
    lastSeenAt: device.lastSeenAt?.toISOString() ?? null,
    current: device.id === currentDeviceId,
  };
}

@ApiTags('devices')
@ApiBearerAuth()
@Controller('devices')
export class DevicesController {
  constructor(private readonly devices: DevicesService) {}

  @Get()
  @ApiOperation({ summary: 'Phones and TVs signed in to this account.' })
  async list(@CurrentPrincipal() principal: Principal): Promise<{ items: DeviceView[] }> {
    const devices = await this.devices.listActive(principal.userId);
    return { items: devices.map((device) => toDeviceView(device, principal.deviceId)) };
  }

  @Patch(':id')
  @ApiOperation({ summary: 'Rename a device.' })
  async rename(
    @CurrentPrincipal() principal: Principal,
    @Param('id') id: string,
    @Body() body: RenameDeviceDto,
  ): Promise<DeviceView> {
    return toDeviceView(
      await this.devices.rename(principal.userId, id, body.name),
      principal.deviceId,
    );
  }

  @Delete(':id')
  @HttpCode(204)
  @ApiOperation({
    summary: 'Remove a device: it is signed out at once. Removing this device signs you out.',
  })
  async remove(@CurrentPrincipal() principal: Principal, @Param('id') id: string): Promise<void> {
    await this.devices.revoke(principal.userId, id);
  }
}
