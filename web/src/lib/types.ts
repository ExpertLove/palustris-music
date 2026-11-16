export interface Track {
  id: string
  title: string
  artist: string
  album?: string | null
  duration: number
  thumb?: string | null
  artistId?: string
  albumId?: string
  /** Почему трек в «Моей волне». */
  why?: string
}

export interface HistItem extends Track {
  playedAt: number
  plays: number
}

export interface Profile {
  email: string
  name: string
  genres: string[]
  created: number
  likes: number
  playlists: number
  plays: number
  sessions: number
  verified: boolean
  consentAt: number
}

export interface PlaylistRow {
  id: number
  name: string
  count: number
  thumbs: string[]
  share: string | null
}

export interface PlaylistFull {
  id: number
  name: string
  share: string | null
  items: Track[]
}

export interface ArtistCard {
  id: string
  name: string
  thumb: string | null
  sub: string
}

export interface AlbumCard {
  id: string
  title: string
  artist: string
  sub: string
  thumb: string | null
  kind: 'album' | 'single' | 'playlist'
}

export interface SearchAll {
  top: ArtistCard | null
  tracks: Track[]
  artists: ArtistCard[]
  albums: AlbumCard[]
  playlists: AlbumCard[]
}

export interface ArtistPage {
  id: string
  name: string
  thumb: string | null
  listeners: string
  description: string
  tracks: Track[]
  allTracks: string | null
  albums: AlbumCard[]
  singles: AlbumCard[]
  playlists: AlbumCard[]
  similar: ArtistCard[]
}

export interface Collection {
  id: string
  kind: 'album' | 'single' | 'playlist'
  title: string
  artist: string
  artistId: string | null
  sub: string
  thumb: string | null
  description: string
  tracks: Track[]
}

export interface LibraryArtist {
  name: string
  count: number
  thumb: string | null
}

export interface Lyrics {
  synced: string | null
  plain: string | null
  instrumental: boolean
  found: boolean
}

export interface SharedPlaylist {
  name: string
  owner: string
  share: string
  items: Track[]
}
