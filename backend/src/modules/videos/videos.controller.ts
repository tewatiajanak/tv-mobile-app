import {
  Body,
  Controller,
  Delete,
  Get,
  HttpCode,
  Param,
  Patch,
  Post,
  Put,
  Res,
} from '@nestjs/common';
import {
  ApiBearerAuth,
  ApiOperation,
  ApiProperty,
  ApiPropertyOptional,
  ApiTags,
} from '@nestjs/swagger';
import { Video } from '@prisma/client';
import { Transform } from 'class-transformer';
import {
  IsInt,
  IsNotEmpty,
  IsOptional,
  IsString,
  IsUUID,
  Matches,
  Max,
  MaxLength,
  Min,
} from 'class-validator';
import type { Response } from 'express';
import { CurrentPrincipal, Principal } from '../../common/decorators/current-principal.decorator';
import { VideosService } from './videos.service';

const trim = ({ value }: { value: unknown }): unknown =>
  typeof value === 'string' ? value.trim() : value;
const MAX_MS = 2_000_000_000;

class CreateVideoDto {
  @ApiPropertyOptional({ description: 'Client-generated UUID; makes the request safe to retry.' })
  @IsOptional()
  @IsUUID()
  id?: string;

  @ApiProperty({ example: 'https://cdn.example.com/a/My.Holiday.2024.mp4' })
  @IsString()
  @IsNotEmpty({ message: 'Paste a video link' })
  @MaxLength(2048, { message: 'That link is too long' })
  sourceUrl!: string;

  @ApiPropertyOptional({ description: 'Defaults to a title derived from the link.' })
  @IsOptional()
  @Transform(trim)
  @IsString()
  @MaxLength(200, { message: 'Title is too long' })
  title?: string;

  @ApiPropertyOptional({ description: 'File size in bytes, if the saving device could read it.' })
  @IsOptional()
  @IsInt()
  @Min(0)
  @Max(Number.MAX_SAFE_INTEGER)
  sizeBytes?: number;

  @ApiPropertyOptional({ example: 'MP4', description: 'Short container/stream name for display.' })
  @IsOptional()
  @Matches(/^[A-Za-z0-9]{1,8}$/, { message: 'format is not valid' })
  format?: string;

  @ApiPropertyOptional({ description: 'A picture the link advertises (og:image). http(s) only.' })
  @IsOptional()
  @Matches(/^https?:\/\/[^\s<>"']+$/i, { message: 'thumbnailUrl is not valid' })
  @MaxLength(2048)
  thumbnailUrl?: string;
}

class RenameVideoDto {
  @ApiProperty()
  @Transform(trim)
  @IsString()
  @IsNotEmpty({ message: 'Enter a title' })
  @MaxLength(200, { message: 'Title is too long' })
  title!: string;
}

class PlaybackDto {
  @ApiProperty({ description: 'Where the viewer stopped, in milliseconds.' })
  @IsInt()
  @Min(0)
  @Max(MAX_MS)
  positionMs!: number;

  @ApiPropertyOptional()
  @IsOptional()
  @IsInt()
  @Min(0)
  @Max(MAX_MS)
  durationMs?: number;
}

export interface VideoView {
  id: string;
  title: string;
  sourceUrl: string;
  sourceDomain: string;
  sizeBytes: number | null;
  format: string | null;
  thumbnailUrl: string | null;
  positionMs: number;
  durationMs: number | null;
  version: number;
  createdAt: string;
  updatedAt: string;
}

function toVideoView(video: Video): VideoView {
  return {
    id: video.id,
    title: video.title,
    sourceUrl: video.sourceUrl,
    sourceDomain: video.sourceDomain,
    // BigInt cannot be serialised to JSON; file sizes are far below 2^53.
    sizeBytes: video.sizeBytes === null ? null : Number(video.sizeBytes),
    format: video.format,
    thumbnailUrl: video.thumbnailUrl,
    positionMs: video.positionMs,
    durationMs: video.durationMs,
    version: video.version,
    createdAt: video.createdAt.toISOString(),
    updatedAt: video.updatedAt.toISOString(),
  };
}

@ApiTags('videos')
@ApiBearerAuth()
@Controller('videos')
export class VideosController {
  constructor(private readonly videos: VideosService) {}

  @Post()
  @ApiOperation({
    summary: 'Save a video link. 201 when created, 200 when this id was already saved.',
  })
  async create(
    @CurrentPrincipal() principal: Principal,
    @Body() body: CreateVideoDto,
    @Res({ passthrough: true }) res: Response,
  ): Promise<VideoView> {
    const { video, created } = await this.videos.create(principal.userId, principal.deviceId, body);
    res.status(created ? 201 : 200);
    return toVideoView(video);
  }

  @Get()
  @ApiOperation({ summary: 'The library, newest first.' })
  async list(@CurrentPrincipal() principal: Principal): Promise<{ items: VideoView[] }> {
    return { items: (await this.videos.list(principal.userId)).map(toVideoView) };
  }

  @Patch(':id')
  @ApiOperation({ summary: 'Rename a video.' })
  async rename(
    @CurrentPrincipal() principal: Principal,
    @Param('id') id: string,
    @Body() body: RenameVideoDto,
  ): Promise<VideoView> {
    return toVideoView(await this.videos.rename(principal.userId, id, body.title));
  }

  @Delete(':id')
  @HttpCode(204)
  @ApiOperation({ summary: 'Remove a video from the library.' })
  async remove(@CurrentPrincipal() principal: Principal, @Param('id') id: string): Promise<void> {
    await this.videos.remove(principal.userId, id);
  }

  @Put(':id/playback')
  @HttpCode(204)
  @ApiOperation({ summary: 'Remember where the viewer stopped, for every device.' })
  async playback(
    @CurrentPrincipal() principal: Principal,
    @Param('id') id: string,
    @Body() body: PlaybackDto,
  ): Promise<void> {
    await this.videos.savePosition(principal.userId, id, body.positionMs, body.durationMs);
  }
}
